/** Every displayed leader total identifies whether Written Record has been included. */
export function LeaderCredit({amount, writtenRecordBonus, includesWrittenRecord = true}: {
    amount: number; writtenRecordBonus: number; includesWrittenRecord?: boolean;
}) {
    return <span className="discount-amount"><strong>−${amount}</strong><small>
        {!includesWrittenRecord ? 'Before Written Record' : writtenRecordBonus > 0
            ? `Includes $${writtenRecordBonus} Written Record` : 'No Written Record bonus'}
    </small></span>;
}
