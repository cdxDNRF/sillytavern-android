/**
 * Embedded Android runtime has no external system CLI tools (git, xdg-open, etc.)
 * by design so all git operations route cleanly through isomorphic-git and never
 * spawn child processes.
 */
export function sync(_commandName) {
    return false;
}

export function commandExists(_commandName, callback) {
    if (typeof callback === 'function') {
        queueMicrotask(() => callback(null, false));
        return;
    }
    return Promise.resolve(false);
}

export default commandExists;
