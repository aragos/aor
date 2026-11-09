import assert from 'node:assert/strict';

// Inspect rendered HTML text against composited ancestor backgrounds. SVG game symbols
// have their own fixed palettes; disabled controls are exempt from WCAG text contrast.
function contrastFailures(scope) {
    const rgba = value => value.match(/[\d.]+/g).map(Number);
    const over = (front, back) => front.slice(0, 3).map((channel, index) =>
        channel * (front[3] ?? 1) + back[index] * (1 - (front[3] ?? 1)));
    const luminance = color => color.slice(0, 3).map(channel => {
        const value = channel / 255;
        return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4;
    }).reduce((total, value, index) => total + value * [.2126, .7152, .0722][index], 0);
    const failures = [];
    const walker = document.createTreeWalker(document.querySelector(scope), NodeFilter.SHOW_TEXT);
    while (walker.nextNode()) {
        const node = walker.currentNode;
        const text = node.textContent.trim();
        const element = node.parentElement;
        if (!text || element.closest('svg, script, style, .sr-only, :disabled') ||
            !element.checkVisibility({checkOpacity: true, checkVisibilityCSS: true})) continue;
        const range = document.createRange();
        range.selectNodeContents(node);
        if (![...range.getClientRects()].some(rect => rect.width && rect.height)) continue;
        const ancestors = [];
        for (let current = element; current; current = current.parentElement) ancestors.unshift(current);
        let backgrounds = [[255, 255, 255]];
        for (const ancestor of ancestors) {
            const style = getComputedStyle(ancestor);
            backgrounds = backgrounds.map(back => over(rgba(style.backgroundColor), back));
            // The sign-in glow is the only HTML background gradient. Check both
            // its solid colour and its transparent endpoint against the page.
            if (style.backgroundImage !== 'none') {
                const stops = style.backgroundImage.match(/rgba?\([^)]+\)/g) ?? [];
                backgrounds = backgrounds.flatMap(back => [back, ...stops.map(stop => over(rgba(stop), back))]);
            }
        }
        const style = getComputedStyle(element);
        const foreground = rgba(style.color);
        const ratio = Math.min(...backgrounds.map(background => {
            const values = [luminance(over(foreground, background)), luminance(background)].sort((a, b) => b - a);
            return (values[0] + .05) / (values[1] + .05);
        }));
        const size = parseFloat(style.fontSize);
        const large = size >= 24 || size >= 18.66 && Number(style.fontWeight) >= 700;
        if (ratio + .01 < (large ? 3 : 4.5)) failures.push({
            text: text.slice(0, 65), element: element.tagName + '.' + element.className,
            foreground: style.color, ratio: Number(ratio.toFixed(2)),
        });
    }
    return failures;
}

export async function assertTextContrast(page, description, scope = 'body') {
    const failures = await page.evaluate(`(${contrastFailures})(${JSON.stringify(scope)})`);
    assert.deepEqual(failures, [], `${description}: rendered text must meet WCAG AA contrast`);
}
