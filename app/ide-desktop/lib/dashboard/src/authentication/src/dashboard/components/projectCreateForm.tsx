/** @file Form to create a project. */
import * as react from 'react'
import toast from 'react-hot-toast'

import * as cloudService from '../cloudService'
import * as error from '../../error'
import * as modalProvider from '../../providers/modal'
import CreateForm, * as createForm from './createForm'

export interface ProjectCreateFormProps extends createForm.CreateFormPassthroughProps {
    backend: cloudService.Backend
    directoryId: cloudService.DirectoryId
    onSuccess: () => void
}

// FIXME[sb]: Extract shared shape to a common component.
function ProjectCreateForm(props: ProjectCreateFormProps) {
    const { backend, directoryId, onSuccess, ...passThrough } = props
    const { unsetModal } = modalProvider.useSetModal()

    const [name, setName] = react.useState<string | null>(null)
    const [template, setTemplate] = react.useState<string | null>(null)

    async function onSubmit(event: react.FormEvent) {
        event.preventDefault()
        if (name == null) {
            toast.error('Please provide a project name.')
        } else {
            unsetModal()
            await toast
                .promise(
                    backend.createProject({
                        parentDirectoryId: directoryId,
                        projectName: name,
                        projectTemplateName: template,
                    }),
                    {
                        loading: 'Creating project...',
                        success: 'Sucessfully created project.',
                        error: error.unsafeIntoErrorMessage,
                    }
                )
                .then(onSuccess)
        }
    }

    return (
        <CreateForm title="New Project" onSubmit={onSubmit} {...passThrough}>
            <div className="flex flex-row flex-nowrap m-1">
                <label className="inline-block flex-1 grow m-1" htmlFor="project_name">
                    Name
                </label>
                <input
                    id="project_name"
                    type="text"
                    size={1}
                    className="bg-gray-200 rounded-full flex-1 grow-2 px-2 m-1"
                    onChange={event => {
                        setName(event.target.value)
                    }}
                />
            </div>
            <div className="flex flex-row flex-nowrap m-1">
                {/* FIXME[sb]: Use the array of templates in a dropdown when it becomes available. */}
                <label className="inline-block flex-1 grow m-1" htmlFor="project_template_name">
                    Template
                </label>
                <input
                    id="project_template_name"
                    type="text"
                    size={1}
                    className="bg-gray-200 rounded-full flex-1 grow-2 px-2 m-1"
                    onChange={event => {
                        setTemplate(event.target.value)
                    }}
                />
            </div>
        </CreateForm>
    )
}

export default ProjectCreateForm
