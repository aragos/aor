/** Open static reference material without replacing the player's live game or draft. */
export function HelpLink({topic = '', label = 'Help', compact = true, inline = false}: {
    topic?: string; label?: string; compact?: boolean; inline?: boolean;
}) {
    return <a className={compact ? `help-link${inline ? ' inline-help' : ''}` : 'reference-link'} href={`/help${topic ? `#${topic}` : ''}`}
        target="_blank" rel="noreferrer" aria-label={`Help: ${label}`} title={label}>
        {compact ? '?' : label}
    </a>;
}
