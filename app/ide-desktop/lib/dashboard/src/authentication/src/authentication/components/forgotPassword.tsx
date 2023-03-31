/** @file Container responsible for rendering and interactions in first half of forgot password
 * flow. */
import * as router from 'react-router-dom'

import * as app from '../../components/app'
import * as auth from '../providers/auth'
import * as common from './common'
import * as hooks from '../../hooks'
import * as icons from '../../components/svg'
import * as utils from '../../utils'

// ======================
// === ForgotPassword ===
// ======================

function ForgotPassword() {
    const { forgotPassword } = auth.useAuth()

    const [email, bindEmail] = hooks.useInput('')

    return (
        <div className="min-h-screen flex flex-col items-center justify-center bg-gray-300">
            <div
                className={
                    'flex flex-col bg-white shadow-md px-4 sm:px-6 md:px-8 lg:px-10 py-8 rounded-md w-full ' +
                    'max-w-md'
                }
            >
                <div className="font-medium self-center text-xl sm:text-2xl uppercase text-gray-800">
                    Forgot Your Password?
                </div>
                <div className="mt-10">
                    <form
                        onSubmit={utils.handleEvent(async () => {
                            await forgotPassword(email)
                        })}
                    >
                        <div className="flex flex-col mb-6">
                            <label
                                htmlFor="email"
                                className="mb-1 text-xs sm:text-sm tracking-wide text-gray-600"
                            >
                                E-Mail Address:
                            </label>
                            <div className="relative">
                                <common.SvgIcon data={icons.PATHS.at} />

                                <common.Input
                                    {...bindEmail}
                                    id="email"
                                    type="email"
                                    name="email"
                                    placeholder="E-Mail Address"
                                />
                            </div>
                        </div>
                        <div className="flex w-full">
                            <button
                                type="submit"
                                className={
                                    'flex items-center justify-center focus:outline-none text-white text-sm ' +
                                    'sm:text-base bg-blue-600 hover:bg-blue-700 rounded py-2 w-full transition ' +
                                    'duration-150 ease-in'
                                }
                            >
                                <span className="mr-2 uppercase">Send link</span>
                                <span>
                                    <icons.Svg data={icons.PATHS.rightArrow} />
                                </span>
                            </button>
                        </div>
                    </form>
                </div>
                <div className="flex justify-center items-center mt-6">
                    <router.Link
                        to={app.LOGIN_PATH}
                        className={
                            'inline-flex items-center font-bold text-blue-500 hover:text-blue-700 text-xs ' +
                            'text-center'
                        }
                    >
                        <span>
                            <icons.Svg data={icons.PATHS.goBack} />
                        </span>
                        <span className="ml-2">Go back to login</span>
                    </router.Link>
                </div>
            </div>
        </div>
    )
}

export default ForgotPassword
