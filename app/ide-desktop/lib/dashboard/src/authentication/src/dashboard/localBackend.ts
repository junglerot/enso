/** @file Module containing the API client for the local backend API.
 *
 * Each exported function in the {@link LocalBackend} in this module corresponds to an API endpoint.
 * The functions are asynchronous and return a {@link Promise} that resolves to the response from
 * the API. */
import * as backend from './backend'
import * as errorModule from '../error'
import * as projectManager from './projectManager'

// ========================
// === Helper functions ===
// ========================

/** Convert a {@link projectManager.IpWithSocket} to a {@link backend.Address}. */
function ipWithSocketToAddress(ipWithSocket: projectManager.IpWithSocket) {
    return backend.Address(`ws://${ipWithSocket.host}:${ipWithSocket.port}`)
}

// ====================
// === LocalBackend ===
// ====================

/** Class for sending requests to the Project Manager API endpoints.
 * This is used instead of the cloud backend API when managing local projects from the dashboard. */
export class LocalBackend implements Partial<backend.Backend> {
    static currentlyOpeningProjectId: backend.ProjectId | null = null
    static currentlyOpenProjects = new Map<projectManager.ProjectId, projectManager.OpenProject>()
    readonly type = backend.BackendType.local
    private readonly projectManager = projectManager.ProjectManager.default()

    /** Create a {@link LocalBackend}. */
    constructor() {
        if (IS_DEV_MODE) {
            // @ts-expect-error This exists only for debugging purposes. It does not have types
            // because it MUST NOT be used in this codebase.
            window.localBackend = this
        }
    }

    /** Return a list of assets in a directory.
     *
     * @throws An error if the JSON-RPC call fails. */
    async listDirectory(): Promise<backend.Asset[]> {
        const result = await this.projectManager.listProjects({})
        return result.projects.map(project => ({
            type: backend.AssetType.project,
            id: project.id,
            title: project.name,
            modifiedAt: project.lastOpened ?? project.created,
            parentId: backend.DirectoryId(''),
            permissions: [],
            projectState: {
                type: LocalBackend.currentlyOpenProjects.has(project.id)
                    ? backend.ProjectState.opened
                    : project.id === LocalBackend.currentlyOpeningProjectId
                    ? backend.ProjectState.openInProgress
                    : project.lastOpened != null
                    ? backend.ProjectState.closed
                    : backend.ProjectState.created,
            },
        }))
    }

    /** Return a list of projects belonging to the current user.
     *
     * @throws An error if the JSON-RPC call fails. */
    async listProjects(): Promise<backend.ListedProject[]> {
        const result = await this.projectManager.listProjects({})
        return result.projects.map(project => ({
            name: project.name,
            organizationId: '',
            projectId: project.id,
            packageName: project.name,
            state: {
                type: backend.ProjectState.created,
            },
            jsonAddress: null,
            binaryAddress: null,
        }))
    }

    /** Create a project.
     *
     * @throws An error if the JSON-RPC call fails. */
    async createProject(body: backend.CreateProjectRequestBody): Promise<backend.CreatedProject> {
        const project = await this.projectManager.createProject({
            name: projectManager.ProjectName(body.projectName),
            ...(body.projectTemplateName != null
                ? { projectTemplate: body.projectTemplateName }
                : {}),
            missingComponentAction: projectManager.MissingComponentAction.install,
        })
        return {
            name: body.projectName,
            organizationId: '',
            projectId: project.projectId,
            packageName: body.projectName,
            state: {
                type: backend.ProjectState.created,
            },
        }
    }

    /** Close the project identified by the given project ID.
     *
     * @throws An error if the JSON-RPC call fails. */
    async closeProject(projectId: backend.ProjectId, title: string | null): Promise<void> {
        if (LocalBackend.currentlyOpeningProjectId === projectId) {
            LocalBackend.currentlyOpeningProjectId = null
        }
        LocalBackend.currentlyOpenProjects.delete(projectId)
        try {
            await this.projectManager.closeProject({ projectId })
            return
        } catch (error) {
            throw new Error(
                `Unable to close project ${
                    title != null ? `'${title}'` : `with ID '${projectId}'`
                }: ${errorModule.tryGetMessage(error) ?? 'unknown error'}.`
            )
        }
    }

    /** Close the project identified by the given project ID.
     *
     * @throws An error if the JSON-RPC call fails. */
    async getProjectDetails(projectId: backend.ProjectId): Promise<backend.Project> {
        const cachedProject = LocalBackend.currentlyOpenProjects.get(projectId)
        if (cachedProject == null) {
            const result = await this.projectManager.listProjects({})
            const project = result.projects.find(listedProject => listedProject.id === projectId)
            const engineVersion = project?.engineVersion
            if (project == null) {
                throw new Error(`The project ID '${projectId}' is invalid.`)
            } else if (engineVersion == null) {
                throw new Error(`The project '${project.name}' does not have an engine version.`)
            } else {
                return {
                    name: project.name,
                    engineVersion: {
                        lifecycle: backend.detectVersionLifecycle(engineVersion),
                        value: engineVersion,
                    },
                    ideVersion: {
                        lifecycle: backend.detectVersionLifecycle(engineVersion),
                        value: engineVersion,
                    },
                    jsonAddress: null,
                    binaryAddress: null,
                    organizationId: '',
                    packageName: project.name,
                    projectId,
                    state: {
                        type:
                            projectId === LocalBackend.currentlyOpeningProjectId
                                ? backend.ProjectState.openInProgress
                                : project.lastOpened != null
                                ? backend.ProjectState.closed
                                : backend.ProjectState.created,
                    },
                }
            }
        } else {
            return {
                name: cachedProject.projectName,
                engineVersion: {
                    lifecycle: backend.detectVersionLifecycle(cachedProject.engineVersion),
                    value: cachedProject.engineVersion,
                },
                ideVersion: {
                    lifecycle: backend.detectVersionLifecycle(cachedProject.engineVersion),
                    value: cachedProject.engineVersion,
                },
                jsonAddress: ipWithSocketToAddress(cachedProject.languageServerJsonAddress),
                binaryAddress: ipWithSocketToAddress(cachedProject.languageServerBinaryAddress),
                organizationId: '',
                packageName: cachedProject.projectName,
                projectId,
                state: {
                    type: backend.ProjectState.opened,
                },
            }
        }
    }

    /** Prepare a project for execution.
     *
     * @throws An error if the JSON-RPC call fails. */
    async openProject(
        projectId: backend.ProjectId,
        _body: backend.OpenProjectRequestBody | null,
        title: string | null
    ): Promise<void> {
        LocalBackend.currentlyOpeningProjectId = projectId
        if (!LocalBackend.currentlyOpenProjects.has(projectId)) {
            try {
                const project = await this.projectManager.openProject({
                    projectId,
                    missingComponentAction: projectManager.MissingComponentAction.install,
                })
                LocalBackend.currentlyOpenProjects.set(projectId, project)
                return
            } catch (error) {
                throw new Error(
                    `Unable to open project ${
                        title != null ? `'${title}'` : `with ID '${projectId}'`
                    }: ${errorModule.tryGetMessage(error) ?? 'unknown error'}.`
                )
            }
        }
    }

    /** Change the name of a project.
     *
     * @throws An error if the JSON-RPC call fails. */
    async projectUpdate(
        projectId: backend.ProjectId,
        body: backend.ProjectUpdateRequestBody
    ): Promise<backend.UpdatedProject> {
        if (body.ami != null) {
            throw new Error('Cannot change project AMI on local backend.')
        } else {
            if (body.projectName != null) {
                await this.projectManager.renameProject({
                    projectId,
                    name: projectManager.ProjectName(body.projectName),
                })
            }
            const result = await this.projectManager.listProjects({})
            const project = result.projects.find(listedProject => listedProject.id === projectId)
            const engineVersion = project?.engineVersion
            if (project == null) {
                throw new Error(`The project ID '${projectId}' is invalid.`)
            } else if (engineVersion == null) {
                throw new Error(`The project '${project.name}' does not have an engine version.`)
            } else {
                return {
                    ami: null,
                    engineVersion: {
                        lifecycle: backend.VersionLifecycle.stable,
                        value: engineVersion,
                    },
                    ideVersion: {
                        lifecycle: backend.VersionLifecycle.stable,
                        value: engineVersion,
                    },
                    name: project.name,
                    organizationId: '',
                    projectId,
                }
            }
        }
    }

    /** Delete a project.
     *
     * @throws An error if the JSON-RPC call fails. */
    async deleteProject(projectId: backend.ProjectId, title: string | null): Promise<void> {
        if (LocalBackend.currentlyOpeningProjectId === projectId) {
            LocalBackend.currentlyOpeningProjectId = null
        }
        LocalBackend.currentlyOpenProjects.delete(projectId)
        try {
            await this.projectManager.deleteProject({ projectId })
            return
        } catch (error) {
            throw new Error(
                `Unable to delete project ${
                    title != null ? `'${title}'` : `with ID '${projectId}'`
                }: ${errorModule.tryGetMessage(error) ?? 'unknown error'}.`
            )
        }
    }
}
