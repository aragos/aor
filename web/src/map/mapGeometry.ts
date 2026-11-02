export interface Geometry {
    bounds: number[];
    frame: string;
    provinces: Record<string, {path: string; outline: string; label: number[]; area: string;
        pieces: Record<string, {size: number; fit: string; crowded: boolean; points: number[][]}>}>;
    coasts: Record<string, string>;
    areas: Record<string, {path: string; label: number[]}>;
    seas: Record<string, {path: string; label: number[]; labelAngle: number; color: string; area: string | null}>;
    terrain: string;
    supports: Record<string, string>;
}

export class UnsupportedMapGeometry extends Error {}

/** A catalog's recorded topology chooses its drawing; unknown versions never use a newer map. */
export function geometryKey(version: string, map: string | boolean | undefined): string {
    return `${version}:${map === 'EUROPEAN' ? 'EUROPEAN' : 'STANDARD'}`;
}

/** Share pending requests across StrictMode mounts and discard failures so explicit retries work. */
export function createGeometryLoader<T>(importers: Readonly<Record<string, () => Promise<T>>>) {
    const sources = new Map(Object.entries(importers));
    const pending = new Map<string, Promise<T>>();
    const loaded = new Map<string, T>();
    return {
        peek: (key: string) => loaded.get(key),
        load(key: string): Promise<T> {
            if (loaded.has(key)) return Promise.resolve(loaded.get(key)!);
            const existing = pending.get(key);
            if (existing) return existing;
            const source = sources.get(key);
            if (!source) return Promise.reject(new UnsupportedMapGeometry(`No drawing for ${key}.`));
            const request = Promise.resolve().then(source).then(geometry => {
                loaded.set(key, geometry);
                pending.delete(key);
                return geometry;
            }, error => {
                pending.delete(key);
                throw error;
            });
            pending.set(key, request);
            return request;
        },
    };
}
