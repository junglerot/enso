/** @file Immutably shallowly merge an object with a partial update. */

// =============
// === merge ===
// =============

/** Prevents generic parameter inference by hiding the type parameter behind a conditional type. */
type NoInfer<T> = [T][T extends T ? 0 : never]

/** Immutably shallowly merge an object with a partial update.
 * Does not preserve classes. Useful for preserving order of properties. */
export function merge<T extends object>(object: T, update: Partial<T>): T {
  return Object.assign({ ...object }, update)
}

/** Return a function to update an object with the given partial update. */
export function merger<T extends object>(update: Partial<NoInfer<T>>): (object: T) => T {
  return object => Object.assign({ ...object }, update)
}

// =====================
// === unsafeEntries ===
// =====================

/** Return the entries of an object. UNSAFE only when it is possible for an object to have
 * extra keys. */
export function unsafeEntries<T extends object>(
  object: T
): { [K in keyof T]: [K, T[K]] }[keyof T][] {
  // @ts-expect-error This is intentionally a wrapper function with a different type.
  return Object.entries(object)
}
