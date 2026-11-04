import standard13 from './catalog13BoardGeometry.json?url';
import standard14 from './catalog14BoardGeometry.json?url';
import standard15 from './catalog15BoardGeometry.json?url';
import standard16 from './boardGeometry.json?url';
import european15 from './europeanBoardGeometry.json?url';
import {createGeometryLoader} from './mapGeometry';
import type {Geometry} from './mapGeometry';

async function loadGeometry(url: string): Promise<Geometry> {
    const response = await fetch(url, {signal: AbortSignal.timeout(15000)});
    if (!response.ok) throw new Error(`Map drawing request failed: ${response.status}.`);
    return response.json();
}

// Import URLs only: the browser downloads one recorded drawing when its map is first opened.
export const mapGeometry = createGeometryLoader<Geometry>({
    'ah1996-catalog-13:STANDARD': () => loadGeometry(standard13),
    'ah1996-catalog-14:STANDARD': () => loadGeometry(standard14),
    'ah1996-catalog-15:STANDARD': () => loadGeometry(standard15),
    'ah1996-catalog-15:EUROPEAN': () => loadGeometry(european15),
    'ah1996-catalog-16:STANDARD': () => loadGeometry(standard16),
    'ah1996-catalog-16:EUROPEAN': () => loadGeometry(european15),
});
