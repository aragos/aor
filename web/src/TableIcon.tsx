/** Shared quantity symbols from the approved game-table design. */
export function TableIcon({kind}: {kind: 'cash' | 'income' | 'misery' | 'ship' | 'cards' | 'token' | 'advances' | 'map' | 'commodities' | 'activity' | 'players'}) {
    return <svg className="table-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        {kind === 'cash' && <><circle cx="11" cy="11" r="7"/><path d="M10 7h3M9 10h4M9 13h4M11 7v7M17 7a7 7 0 0 1-1 13"/></>}
        {kind === 'income' && <path d="M4 19h16M5 15l5-5 4 3 6-9M14 4h6v6"/>}
        {kind === 'misery' && <><circle cx="12" cy="12" r="9"/><path d="M8 9h1m6 0h1M8 17q4-6 8 0"/></>}
        {kind === 'ship' && <path d="M3 16h18l-4 5H7ZM12 3v13M10 4L4 13h6M14 6l6 7h-6"/>}
        {kind === 'cards' && <><rect x="7" y="3" width="13" height="17" rx="2"/><path d="M4 6H2v15a1 1 0 0 0 1 1h13M11 8h5M11 12h5"/></>}
        {kind === 'token' && <><rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 9h8M12 9v8"/></>}
        {kind === 'advances' && <path d="M12 5v15M12 5Q7 2 2 4v15q5-2 10 1 5-3 10-1V4q-5-2-10 1Z"/>}
        {kind === 'map' && <><path d="m3 6 6-3 6 3 6-3v15l-6 3-6-3-6 3ZM9 3v15M15 6v15"/><path d="m5 12 2-1m4-1 2 1m4 1 2-1"/></>}
        {kind === 'commodities' && <><path d="m8 3 1.5 5h5L16 3l-4 1ZM9 8c-2 3-6 6-6 10 0 3 4 4 9 4s9-1 9-4c0-4-4-7-6-10ZM8 8h8"/><path d="M14.5 12h-3a1.5 1.5 0 0 0 0 3h1a1.5 1.5 0 0 1 0 3h-3M12 11v8"/></>}
        {kind === 'activity' && <><rect x="4" y="3" width="16" height="18" rx="2"/><path d="m7 8 1 1 2-2m-3 7 1 1 2-2M13 8h4M13 14h4M7 18h10"/></>}
        {kind === 'players' && <><circle cx="9" cy="7" r="4"/><path d="M2 21v-3a7 7 0 0 1 14 0v3M16 4a4 4 0 0 1 0 8M19 15a6 6 0 0 1 3 6"/></>}
    </svg>;
}
