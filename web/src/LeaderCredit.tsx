/** Shared qualification for a displayed leader total or a group with the same enhancement. */
export function leaderCreditNote(writtenRecordBonus: number, includesWrittenRecord = true): string {
    if (!includesWrittenRecord) return 'Before Written Record';
    return writtenRecordBonus > 0
        ? `Includes $${writtenRecordBonus} Written Record` : 'No Written Record bonus';
}

/** Compact totals retain their qualification for pointer and assistive-technology users. */
export function LeaderCredit({amount, writtenRecordBonus, includesWrittenRecord = true, compact = false}: {
    amount: number; writtenRecordBonus: number; includesWrittenRecord?: boolean; compact?: boolean;
}) {
    const note = leaderCreditNote(writtenRecordBonus, includesWrittenRecord);
    return <span className="discount-amount" title={compact ? note : undefined}>
        <strong>−${amount}</strong>{compact ? <span className="sr-only"> {note}</span> : <small>{note}</small>}
    </span>;
}
