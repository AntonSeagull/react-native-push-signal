import type { PushDiagnostics, PushProvider } from './types';

/**
 * Error thrown by `initialize` and `getCredentials`.
 *
 * The message already contains a human-readable, actionable hint when the
 * standard Google service (FCM/GMS) cannot be used. Structured details are
 * available on the instance so the host app can decide how to log or report them.
 */
export class PushSignalError extends Error {
  /** Machine-readable code, e.g. `E_GMS_MISSING` or `E_FCM_TOKEN`. */
  readonly code: string;
  /** Suggested alternative push provider, when the standard service is unusable. */
  provider?: PushProvider;
  /** Actionable sentence: which service to use instead of the standard one. */
  hint?: string;
  /** Full device/provider diagnostics if they could be collected. */
  diagnostics?: PushDiagnostics;
  /** Original error from the native layer. */
  override readonly cause?: unknown;

  constructor(code: string, message: string, cause?: unknown) {
    super(message);
    this.name = 'PushSignalError';
    this.code = code;
    this.cause = cause;
    Object.setPrototypeOf(this, PushSignalError.prototype);
  }
}
