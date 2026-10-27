/** Display aliases replace complete identifier tokens, never fragments of card references. */
export function displayHistoryLine(line: string, identifiers: Record<string, string>): string {
    const ids = Object.keys(identifiers).map(id => id.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    if (!ids.length) return line;
    const pattern = new RegExp(`(^|[^A-Za-z0-9_-])(${ids.join('|')})(?=$|[^A-Za-z0-9_-])`, 'g');
    return line.replace(pattern, (_, separator: string, id: string) => separator + identifiers[id]);
}
