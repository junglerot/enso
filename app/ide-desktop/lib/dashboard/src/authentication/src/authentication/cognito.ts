/** @file Provides {@link Cognito} class which is the entrypoint into the AWS Amplify library.
 *
 * All of the functions used for authentication are provided by the AWS Amplify library, but we
 * provide a thin wrapper around them to make them easier to use. Mainly, we perform some error
 * handling and conditional logic to vary behavior between desktop & cloud.
 *
 * # Error Handling
 *
 * The AWS Amplify library throws errors when authentication fails. We catch these errors and
 * convert them to typed responses. This allows us to exhaustively handle errors by providing
 * information on the types of errors returned, in function return types.
 *
 * Not all errors are caught and handled. Any errors not relevant to business logic or control flow
 * are allowed to propagate up.
 *
 * Errors are grouped by the AWS Amplify function that throws the error (e.g., `signUp`). This is
 * because the Amplify library reuses some error codes for multiple kinds of errors. For example,
 * the `UsernameExistsException` error code is used for both the `signUp` and `confirmSignUp`
 * functions. This would be fine if the same error code didn't meet different conditions for each
 *
 * Each error must provide a way to disambiguate from other errors. Typically, our error definitions
 * include an `internalCode` field, which is the code that the Amplify library uses to identify the
 * error.
 *
 * Some errors also include an `internalMessage` field, which is the message that the Amplify
 * library associates with the error. This field is used to distinguish between errors that have the
 * same `internalCode`.
 *
 * Amplify reuses some codes for multiple kinds of errors. In the case of ambiguous errors, the
 * `kind` field provides a unique string that can be used to brand the error in place of the
 * `internalCode`, when rethrowing the error. */
import * as amplify from '@aws-amplify/auth'
import * as cognito from 'amazon-cognito-identity-js'
import * as results from 'ts-results'

import * as config from './config'
import * as platformModule from '../platform'

// ====================
// === AmplifyError ===
// ====================

/** Error thrown by the AWS Amplify library when an Amplify error occurs.
 *
 * Some Amplify errors (e.g., network connectivity errors) can not be resolved within the
 * application. Un-resolvable errors are allowed to flow up to the top-level error handler. Errors
 * that can be resolved must be caught and handled as early as possible. The {@link KNOWN_ERRORS}
 * map lists the Amplify errors that we want to catch and convert to typed responses.
 *
 * # Handling Amplify Errors
 *
 * Use the {@link isAmplifyError} function to check if an `unknown` error is an
 * {@link AmplifyError}. If it is, use the {@link intoAmplifyErrorOrThrow} function to convert it
 * from `unknown` to a typed object. Then, use the {@link KNOWN_ERRORS} to see if the error is one
 * that must be handled by the application (i.e., it is an error that is relevant to our business
 * logic). */
interface AmplifyError extends Error {
    /** Error code for disambiguating the error. */
    code: string
}

/** Hints to TypeScript if we can safely cast an `unknown` error to an {@link AmplifyError}. */
function isAmplifyError(error: unknown): error is AmplifyError {
    if (error && typeof error === 'object') {
        return 'code' in error && 'message' in error && 'name' in error
    }
    return false
}

/** Converts the `unknown` error into an {@link AmplifyError} and returns it, or re-throws it if
 * conversion is not possible.
 * @throws If the error is not an amplify error. */
function intoAmplifyErrorOrThrow(error: unknown): AmplifyError {
    if (isAmplifyError(error)) {
        return error
    } else {
        throw error
    }
}

// ===============
// === Cognito ===
// ===============

/** Interface defining the methods provided by this module for interacting with Cognito for
 * authentication.
 *
 * The methods defined in this API are thin wrappers around the AWS Amplify library with error
 * handling added. This way, the methods don't throw all errors, but define exactly which errors
 * they return. The caller can then handle them via pattern matching on the {@link results.Result}
 * type. */
export class Cognito {
    constructor(
        private readonly platform: platformModule.Platform,
        amplifyConfig: config.AmplifyConfig
    ) {
        /** Amplify expects `Auth.configure` to be called before any other `Auth` methods are
         * called. By wrapping all the `Auth` methods we care about and returning an `Cognito` API
         * object containing them, we ensure that `Auth.configure` is called before any other `Auth`
         * methods are called. */
        const nestedAmplifyConfig = config.toNestedAmplifyConfig(amplifyConfig)
        amplify.Auth.configure(nestedAmplifyConfig)
    }

    // === Interface `impl`s ===

    /** Returns the current user's session, or `None` if the user is not logged in.
     *
     * Will refresh the session if it has expired. */
    async userSession(this: void) {
        const amplifySession = await getAmplifyCurrentSession()
        return amplifySession.map(parseUserSession).toOption()
    }
    /** Sign up with with username and password.
     *
     * Does not rely on federated identity providers (e.g., Google or GitHub). */
    signUp(username: string, password: string) {
        return signUp(username, password, this.platform)
    }
    /** Sends the email address verification code.
     *
     * The user will receive a link in their email. The user must click the link to go to the email
     * verification page. The email verification page will parse the verification code from the URL.
     * If the verification code matches, the email address is marked as verified. Once the email
     * address is verified, the user can sign in. */
    confirmSignUp(email: string, code: string) {
        return confirmSignUp(email, code)
    }
}

// ===================
// === UserSession ===
// ===================

/** User's session, provides information for identifying and authenticating the user. */
export interface UserSession {
    /** User's email address, used to uniquely identify the user.
     *
     * Provided by the identity provider the user used to log in. One of:
     *
     * - GitHub,
     * - Google, or
     * - Email. */
    email: string
    /** User's access token, used to authenticate the user (e.g., when making API calls). */
    accessToken: string
}

/** Returns the current `CognitoUserSession` if the user is logged in, or `CurrentSessionErrorKind`
 * otherwise.
 *
 * Will refresh the session if it has expired. */
async function getAmplifyCurrentSession() {
    const currentSession = await results.Result.wrapAsync(() => amplify.Auth.currentSession())
    return currentSession.mapErr(intoCurrentSessionErrorKind)
}

/** Parses a `CognitoUserSession` into a `UserSession`.
 * @throws If the `email` field of the payload is not a string. */
function parseUserSession(session: cognito.CognitoUserSession): UserSession {
    const payload: Record<string, unknown> = session.getIdToken().payload
    const email = payload.email
    /** The `email` field is mandatory, so we assert that it exists and is a string. */
    if (typeof email !== 'string') {
        throw new Error('Payload does not have an email field.')
    }
    const accessToken = session.getAccessToken().getJwtToken()
    return { email, accessToken }
}

const CURRENT_SESSION_NO_CURRENT_USER_ERROR = {
    internalMessage: 'No current user',
    kind: 'NoCurrentUser',
} as const

type CurrentSessionErrorKind = (typeof CURRENT_SESSION_NO_CURRENT_USER_ERROR)['kind']

function intoCurrentSessionErrorKind(error: unknown): CurrentSessionErrorKind {
    if (error === CURRENT_SESSION_NO_CURRENT_USER_ERROR.internalMessage) {
        return CURRENT_SESSION_NO_CURRENT_USER_ERROR.kind
    } else {
        throw error
    }
}

// ==============
// === SignUp ===
// ==============

function signUp(username: string, password: string, platform: platformModule.Platform) {
    return results.Result.wrapAsync(async () => {
        const params = intoSignUpParams(username, password, platform)
        await amplify.Auth.signUp(params)
    }).then(result => result.mapErr(intoAmplifyErrorOrThrow).mapErr(intoSignUpErrorOrThrow))
}

function intoSignUpParams(
    username: string,
    password: string,
    platform: platformModule.Platform
): amplify.SignUpParams {
    return {
        username,
        password,
        attributes: {
            email: username,
            /** Add a custom attribute indicating whether the user is signing up from the desktop.
             * This is used to determine the schema used in the callback links sent in the
             * verification emails. For example, `http://` for the Cloud, and `enso://` for the
             * desktop.
             *
             * # Naming Convention
             *
             * It is necessary to disable the naming convention rule here, because the key is
             * expected to appear exactly as-is in Cognito, so we must match it. */
            // eslint-disable-next-line @typescript-eslint/naming-convention
            'custom:fromDesktop': platform === platformModule.Platform.desktop ? 'true' : 'false',
        },
    }
}

const SIGN_UP_USERNAME_EXISTS_ERROR = {
    internalCode: 'UsernameExistsException',
    kind: 'UsernameExists',
} as const

const SIGN_UP_INVALID_PARAMETER_ERROR = {
    internalCode: 'InvalidParameterEx[ception',
    kind: 'InvalidParameter',
} as const

type SignUpErrorKind =
    | (typeof SIGN_UP_INVALID_PARAMETER_ERROR)['kind']
    | (typeof SIGN_UP_USERNAME_EXISTS_ERROR)['kind']

export interface SignUpError {
    kind: SignUpErrorKind
    message: string
}

function intoSignUpErrorOrThrow(error: AmplifyError): SignUpError {
    if (error.code === SIGN_UP_USERNAME_EXISTS_ERROR.internalCode) {
        return {
            kind: SIGN_UP_USERNAME_EXISTS_ERROR.kind,
            message: error.message,
        }
    } else if (error.code === SIGN_UP_INVALID_PARAMETER_ERROR.internalCode) {
        return {
            kind: SIGN_UP_INVALID_PARAMETER_ERROR.kind,
            message: error.message,
        }
    }

    throw error
}

// =====================
// === ConfirmSignUp ===
// =====================

async function confirmSignUp(email: string, code: string) {
    return results.Result.wrapAsync(async () => {
        await amplify.Auth.confirmSignUp(email, code)
    })
        .then(result => result.mapErr(intoAmplifyErrorOrThrow))
        .then(result => result.mapErr(intoConfirmSignUpErrorOrThrow))
}

const CONFIRM_SIGN_UP_USER_ALREADY_CONFIRMED_ERROR = {
    internalCode: 'NotAuthorizedException',
    internalMessage: 'User cannot be confirmed. Current status is CONFIRMED',
    kind: 'UserAlreadyConfirmed',
} as const

type ConfirmSignUpErrorKind = (typeof CONFIRM_SIGN_UP_USER_ALREADY_CONFIRMED_ERROR)['kind']

export interface ConfirmSignUpError {
    kind: ConfirmSignUpErrorKind
    message: string
}

function intoConfirmSignUpErrorOrThrow(error: AmplifyError): ConfirmSignUpError {
    if (error.code === CONFIRM_SIGN_UP_USER_ALREADY_CONFIRMED_ERROR.internalCode) {
        if (error.message === CONFIRM_SIGN_UP_USER_ALREADY_CONFIRMED_ERROR.internalMessage) {
            return {
                /** Don't re-use the original `error.code` here because Amplify overloads the same
                 * code for multiple kinds of errors. We replace it with a custom code that has no
                 * ambiguity. */
                kind: CONFIRM_SIGN_UP_USER_ALREADY_CONFIRMED_ERROR.kind,
                message: error.message,
            }
        }
    }

    throw error
}
