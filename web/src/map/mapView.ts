/** Map coordinates and extents, in the checked-in board geometry's units. */
export interface MapView {x: number; y: number; w: number; h: number}

/** Positive rendered SVG content dimensions, in CSS pixels. */
export interface MapViewport {width: number; height: number}

/** The largest view that fills the viewport without extending outside the board. */
function maximumWidth(bounds: MapView, viewport: MapViewport): number {
    return Math.min(bounds.w, bounds.h * viewport.width / viewport.height);
}

/**
 * Fill the viewport's width initially. A viewport taller than the board's aspect ratio must use
 * height-cover instead, because width-fit would otherwise expose space above and below the board.
 */
export function fitMapView(bounds: MapView, viewport: MapViewport): MapView {
    const w = maximumWidth(bounds, viewport);
    const h = w * viewport.height / viewport.width;
    return {x: bounds.x + (bounds.w - w) / 2, y: bounds.y + (bounds.h - h) / 2, w, h};
}

/** Keep both visible dimensions and the camera origin within the board's rectangular extent. */
export function clampMapView(view: MapView, bounds: MapView, viewport: MapViewport): MapView {
    const maxWidth = maximumWidth(bounds, viewport);
    const w = Math.min(maxWidth, Math.max(Math.min(500, maxWidth), view.w));
    const h = w * viewport.height / viewport.width;
    return {w, h,
        x: Math.max(bounds.x, Math.min(bounds.x + bounds.w - w, view.x)),
        y: Math.max(bounds.y, Math.min(bounds.y + bounds.h - h, view.y))};
}

/** Resize around the current center while retaining the current zoom width whenever it still fits. */
export function resizeMapView(view: MapView, bounds: MapView, viewport: MapViewport): MapView {
    const fitted = clampMapView(view, bounds, viewport);
    return clampMapView({...fitted,
        x: view.x + (view.w - fitted.w) / 2,
        y: view.y + (view.h - fitted.h) / 2}, bounds, viewport);
}

/** Zoom around a normalized viewport anchor; wheel, touch and button zoom share the same limits. */
export function zoomMapView(view: MapView, bounds: MapView, viewport: MapViewport,
    factor: number, anchor = {x: .5, y: .5}): MapView {
    const next = clampMapView({...view, w: view.w * factor}, bounds, viewport);
    return clampMapView({...next,
        x: view.x + (view.w - next.w) * anchor.x,
        y: view.y + (view.h - next.h) * anchor.y}, bounds, viewport);
}
