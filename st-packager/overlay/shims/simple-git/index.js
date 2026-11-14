import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import git from 'isomorphic-git';
import http from 'isomorphic-git/http/node';

export const CheckRepoActions = Object.freeze({
    BARE: 'bare',
    IS_REPO_ROOT: 'root',
    IS_REPO_IN_TREE: 'tree',
});

const DEFAULT_AUTHOR = Object.freeze({
    name: 'SillyTavern',
    email: 'sillytavern@localhost',
});

/**
 * Resolves a directory from simpleGit constructor arguments.
 * @param {string | { baseDir?: string }} [baseDirOrOptions]
 * @returns {string}
 */
function resolveBaseDir(baseDirOrOptions) {
    if (typeof baseDirOrOptions === 'string' && baseDirOrOptions.length > 0) {
        return path.resolve(baseDirOrOptions);
    }
    if (baseDirOrOptions && typeof baseDirOrOptions === 'object' && typeof baseDirOrOptions.baseDir === 'string') {
        return path.resolve(baseDirOrOptions.baseDir);
    }
    return process.cwd();
}

/**
 * Reads all ref OIDs for a list of branch names cleanly.
 * @param {string} dir
 * @param {string[]} names
 * @param {string} prefix
 * @returns {Promise<Record<string, { current: boolean, commit: string, name: string, label: string }>>}
 */
async function buildBranchMap(dir, names, prefix = '', currentName = '') {
    /** @type {Record<string, { current: boolean, commit: string, name: string, label: string }>} */
    const branches = {};
    for (const rawName of names) {
        if (!rawName || rawName === 'HEAD') continue;
        const fullName = prefix ? `${prefix}${rawName}` : rawName;
        let commit = '';
        try {
            const refPath = prefix ? `refs/remotes/${fullName}` : `refs/heads/${rawName}`;
            commit = await git.resolveRef({ fs, dir, ref: refPath });
        } catch {
            try {
                commit = await git.resolveRef({ fs, dir, ref: fullName });
            } catch {
                commit = '';
            }
        }
        branches[fullName] = {
            current: !prefix && rawName === currentName,
            commit,
            name: fullName,
            label: fullName,
        };
    }
    return branches;
}

class PureJsSimpleGit {
    /**
     * @param {string | { baseDir?: string }} [baseDirOrOptions]
     */
    constructor(baseDirOrOptions) {
        this.baseDir = resolveBaseDir(baseDirOrOptions);
    }

    /**
     * @param {string} [action]
     * @returns {Promise<boolean>}
     */
    async checkIsRepo(action = CheckRepoActions.IS_REPO_IN_TREE) {
        const dotGit = path.join(this.baseDir, '.git');
        if (action === CheckRepoActions.IS_REPO_ROOT) {
            return fs.existsSync(dotGit);
        }
        if (action === CheckRepoActions.BARE) {
            return fs.existsSync(path.join(this.baseDir, 'HEAD')) && !fs.existsSync(dotGit);
        }
        try {
            const root = await git.findRoot({ fs, filepath: this.baseDir });
            return Boolean(root);
        } catch {
            return false;
        }
    }

    /**
     * @param {string} url
     * @param {string} localPath
     * @param {Record<string, any> | string[]} [options]
     * @returns {Promise<void>}
     */
    async clone(url, localPath, options = {}) {
        let depth;
        let branch;
        if (Array.isArray(options)) {
            for (let i = 0; i < options.length; i++) {
                if (options[i] === '--depth' && options[i + 1]) depth = Number(options[++i]);
                if (options[i] === '--branch' && options[i + 1]) branch = String(options[++i]);
            }
        } else if (options && typeof options === 'object') {
            if (options['--depth'] !== undefined) depth = Number(options['--depth']);
            if (options.depth !== undefined) depth = Number(options.depth);
            if (options['--branch']) branch = String(options['--branch']);
            if (options.branch) branch = String(options.branch);
        }

        await git.clone({
            fs,
            http,
            dir: path.resolve(localPath),
            url,
            depth,
            ref: branch,
            singleBranch: depth !== undefined || Boolean(branch),
        });
    }

    /**
     * @param {string} [remote]
     * @param {string[] | Record<string, any>} [options]
     * @returns {Promise<any>}
     */
    async fetch(remote = 'origin', options = []) {
        const args = Array.isArray(options) ? options : [];
        const unshallow = args.includes('--unshallow');
        if (unshallow) {
            const shallowFile = path.join(this.baseDir, '.git', 'shallow');
            if (fs.existsSync(shallowFile)) {
                fs.rmSync(shallowFile, { force: true });
            }
        }

        let singleBranch = true;
        try {
            const fetchSpec = await git.getConfig({ fs, dir: this.baseDir, path: `remote.${remote}.fetch` });
            if (typeof fetchSpec === 'string' && fetchSpec.includes('*')) {
                singleBranch = false;
            }
        } catch {
            // ignore missing config
        }

        if (unshallow) {
            return await git.fetch({
                fs,
                http,
                dir: this.baseDir,
                remote,
                singleBranch: false,
                tags: false,
            });
        }

        const current = await git.currentBranch({ fs, dir: this.baseDir, fullname: false }) || undefined;
        return await git.fetch({
            fs,
            http,
            dir: this.baseDir,
            remote,
            ref: singleBranch ? current : undefined,
            singleBranch,
            tags: false,
        });
    }

    /**
     * @param {string[]} [args]
     * @returns {Promise<{ current: string, all: string[], branches: Record<string, any> }>}
     */
    async branch(args = []) {
        const listArgs = Array.isArray(args) ? args : [];
        const current = (await git.currentBranch({ fs, dir: this.baseDir, fullname: false })) || '';

        if (listArgs.includes('-r') || listArgs.includes('--remotes')) {
            const remoteNames = await git.listBranches({ fs, dir: this.baseDir, remote: 'origin' });
            const branches = await buildBranchMap(this.baseDir, remoteNames, 'origin/', current);
            return {
                current,
                all: Object.keys(branches),
                branches,
            };
        }

        const localNames = await git.listBranches({ fs, dir: this.baseDir });
        const branches = await buildBranchMap(this.baseDir, localNames, '', current);
        return {
            current,
            all: Object.keys(branches),
            branches,
        };
    }

    /**
     * @returns {Promise<{ current: string, all: string[], branches: Record<string, any> }>}
     */
    async branchLocal() {
        return await this.branch([]);
    }

    /**
     * @param {string[]} args
     * @returns {Promise<string>}
     */
    async revparse(args = []) {
        const tokens = Array.isArray(args) ? args : [String(args)];

        if (tokens.includes('--is-shallow-repository')) {
            return fs.existsSync(path.join(this.baseDir, '.git', 'shallow')) ? 'true' : 'false';
        }

        if (tokens.includes('@{u}')) {
            const current = (await git.currentBranch({ fs, dir: this.baseDir, fullname: false })) || 'main';
            const remote = (await git.getConfig({ fs, dir: this.baseDir, path: `branch.${current}.remote` })) || 'origin';
            const mergeRef = await git.getConfig({ fs, dir: this.baseDir, path: `branch.${current}.merge` });
            const branchShort = mergeRef ? String(mergeRef).replace(/^refs\/heads\//, '') : current;
            return `${remote}/${branchShort}`;
        }

        const ref = tokens.find(t => !t.startsWith('-')) || 'HEAD';
        const oid = await git.resolveRef({ fs, dir: this.baseDir, ref });
        if (tokens.includes('--short')) {
            return oid.slice(0, 7);
        }
        return oid;
    }

    /**
     * @param {string} refName
     * @returns {Promise<string>}
     */
    async #resolveAnyRef(refName) {
        const candidates = [refName];
        if (!refName.startsWith('refs/') && refName.includes('/')) {
            candidates.push(`refs/remotes/${refName}`);
        }
        if (!refName.startsWith('refs/')) {
            candidates.push(`refs/heads/${refName}`);
        }
        let lastErr;
        for (const candidate of candidates) {
            try {
                return await git.resolveRef({ fs, dir: this.baseDir, ref: candidate });
            } catch (err) {
                lastErr = err;
            }
        }
        throw lastErr;
    }

    /**
     * @param {{ from?: string, to?: string }} [options]
     * @returns {Promise<{ total: number, all: any[], latest: any }>}
     */
    async log(options = {}) {
        const fromRef = options.from;
        const toRef = options.to || 'HEAD';

        const toOid = await this.#resolveAnyRef(toRef);
        if (!fromRef) {
            const commits = await git.log({ fs, dir: this.baseDir, ref: toOid, depth: 100 });
            return {
                total: commits.length,
                all: commits,
                latest: commits[0] ?? null,
            };
        }

        const fromOid = await this.#resolveAnyRef(fromRef);
        if (fromOid === toOid) {
            return { total: 0, all: [], latest: null };
        }

        let fromCommits = [];
        try {
            fromCommits = await git.log({ fs, dir: this.baseDir, ref: fromOid, depth: 250 });
        } catch {
            fromCommits = [];
        }
        const fromSet = new Set(fromCommits.map(c => c.oid));
        fromSet.add(fromOid);

        let toCommits = [];
        try {
            toCommits = await git.log({ fs, dir: this.baseDir, ref: toOid, depth: 250 });
        } catch {
            toCommits = [];
        }

        const diff = [];
        for (const entry of toCommits) {
            if (fromSet.has(entry.oid)) break;
            diff.push(entry);
        }

        return {
            total: diff.length,
            all: diff,
            latest: diff[0] ?? null,
        };
    }

    /**
     * @param {boolean} [_verbose]
     * @returns {Promise<Array<{ name: string, refs: { fetch: string, push: string } }>>}
     */
    async getRemotes(_verbose = false) {
        const remotes = await git.listRemotes({ fs, dir: this.baseDir });
        return remotes.map(r => ({
            name: r.remote,
            refs: {
                fetch: r.url,
                push: r.url,
            },
        }));
    }

    /**
     * @param {string} [remote]
     * @param {string} [branch]
     * @returns {Promise<void>}
     */
    async pull(remote = 'origin', branch) {
        const targetBranch = branch || (await git.currentBranch({ fs, dir: this.baseDir, fullname: false })) || 'main';
        await git.pull({
            fs,
            http,
            dir: this.baseDir,
            remote,
            ref: targetBranch,
            singleBranch: true,
            author: DEFAULT_AUTHOR,
            committer: DEFAULT_AUTHOR,
        });
    }

    /**
     * @param {string[]} args
     * @returns {Promise<void>}
     */
    async remote(args = []) {
        if (args[0] === 'set-branches') {
            const remoteName = args[1] || 'origin';
            const spec = args[2] === '*'
                ? `+refs/heads/*:refs/remotes/${remoteName}/*`
                : `+refs/heads/${args[2]}:refs/remotes/${remoteName}/${args[2]}`;
            await git.setConfig({
                fs,
                dir: this.baseDir,
                path: `remote.${remoteName}.fetch`,
                value: spec,
            });
        }
    }

    /**
     * @param {string} branchName
     * @returns {Promise<void>}
     */
    async checkout(branchName) {
        await git.checkout({
            fs,
            dir: this.baseDir,
            ref: branchName,
            force: true,
        });
    }

    /**
     * @param {string} localBranch
     * @param {string} startPoint
     * @returns {Promise<void>}
     */
    async checkoutBranch(localBranch, startPoint) {
        const oid = await this.#resolveAnyRef(startPoint);
        await git.branch({
            fs,
            dir: this.baseDir,
            ref: localBranch,
            object: oid,
            checkout: false,
            force: true,
        });
        await git.checkout({
            fs,
            dir: this.baseDir,
            ref: localBranch,
            force: true,
        });
        await git.setConfig({
            fs,
            dir: this.baseDir,
            path: `branch.${localBranch}.remote`,
            value: 'origin',
        });
        await git.setConfig({
            fs,
            dir: this.baseDir,
            path: `branch.${localBranch}.merge`,
            value: `refs/heads/${localBranch}`,
        });
    }

    /**
     * @param {string} [ref]
     * @returns {Promise<string>}
     */
    async show(args = []) {
        const tokens = Array.isArray(args) ? args : [String(args)];
        const ref = tokens[tokens.length - 1] || 'HEAD';
        const oid = await this.#resolveAnyRef(ref);
        const { commit } = await git.readCommit({ fs, dir: this.baseDir, oid });
        const date = new Date(commit.committer.timestamp * 1000);
        return date.toISOString();
    }
}

/**
 * Factory compatible with simple-git default export.
 * @param {string | { baseDir?: string }} [baseDirOrOptions]
 * @returns {PureJsSimpleGit}
 */
export function simpleGit(baseDirOrOptions) {
    return new PureJsSimpleGit(baseDirOrOptions);
}

export default simpleGit;
