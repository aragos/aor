import {NumberInput} from './NumberInput';
import {DecisionAction, DecisionForm} from './DecisionActions';
import {useEffect, useRef, useState} from 'react';
import type {Snapshot} from './api';
import {t} from './messages';
import {CapitalLabel, LabelMessage, PlayerLabel, PlayerLabels} from './IdentityLabel';
import {GameActions} from './GameActions';
import {MapPicker} from './map/MapSelection';
import type {PurchaseQuote, QuotePurchase} from './GameTable';
import {errorCode} from './api';

function bidSubmitLabel(snapshot: Snapshot): string {
    const pending = snapshot.pendingBid !== null;
    if (snapshot.game.stage === 'TOKEN_PURCHASE') {
        return t(pending ? 'controls.replacePurchase' : 'controls.savePurchase');
    }
    return t(pending ? 'controls.replaceBid' : 'controls.saveBid');
}

export function Controls({snapshot, busy, submit, quotePurchase}: {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>; quotePurchase: QuotePurchase}) {
    const [bid, setBid] = useState(String(snapshot.pendingBid ?? 0));
    const [purchaseQuote, setPurchaseQuote] = useState<PurchaseQuote | null>(null);
    const [quoteError, setQuoteError] = useState('');
    const [capital, setCapital] = useState('');
    const [position, setPosition] = useState('');
    const previousBid = useRef(snapshot.pendingBid);
    const previousStage = useRef(snapshot.game.stage);
    useEffect(() => {
        if (previousStage.current !== snapshot.game.stage) {
            setBid(String(snapshot.pendingBid ?? 0));
            previousStage.current = snapshot.game.stage;
            previousBid.current = snapshot.pendingBid;
            return;
        }
        const previous = String(previousBid.current ?? 0);
        // Withdrawal clears readiness while retaining the amount as an editable, unsaved draft.
        if (snapshot.pendingBid !== null) {
            setBid(current => current === previous ? String(snapshot.pendingBid) : current);
        }
        previousBid.current = snapshot.pendingBid;
    }, [snapshot.pendingBid, snapshot.game.stage]);
    const stage = snapshot.game.stage;
    const capitalChoices = snapshot.legalChoices.commands.filter(choice => choice.startsWith('choose-capital '));
    const turnChoices = snapshot.legalChoices.commands.filter(choice => choice.startsWith('choose-turn-position '));
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const isTokenPurchase = stage === 'TOKEN_PURCHASE';
    const eligiblePurchase = isTokenPurchase && me.misery !== null && snapshot.legalChoices.commands.includes('buy-tokens');
    const canAct = snapshot.prompt?.players.includes(snapshot.viewerId) ?? false;
    const amount = Number(bid);
    const validPurchase = bid.trim() !== '' && Number.isInteger(amount) && Math.abs(amount) <= me.cash;
    const quoteRequest = useRef(quotePurchase);
    quoteRequest.current = quotePurchase;
    useEffect(() => {
        setPurchaseQuote(null); setQuoteError('');
        if (!eligiblePurchase || !validPurchase) return;
        let active = true;
        const timer = setTimeout(() => {
            void quoteRequest.current(amount).then(result => {
                if (active && result.revision === snapshot.revision && result.quote.amount === amount) setPurchaseQuote(result);
            }).catch(failure => { if (active) setQuoteError(errorCode(failure)); });
        }, 180);
        return () => { active = false; clearTimeout(timer); };
    }, [amount, eligiblePurchase, snapshot.revision, validPurchase]);
    const quoted = purchaseQuote?.revision === snapshot.revision && purchaseQuote.quote.amount === amount ? purchaseQuote.quote : null;
    const positionLabel = (slot: number) => {
        const order = snapshot.game.provisionalTieOrder;
        if (order.length === 0) return t('controls.firstPosition');
        if (slot === 1) return <LabelMessage text={t('controls.before')} values={{name: <PlayerLabel snapshot={snapshot} id={order[0]}/>}}/>;
        if (slot > order.length) return <LabelMessage text={t('controls.after')} values={{name: <PlayerLabel snapshot={snapshot} id={order[order.length - 1]}/>}}/>;
        return <LabelMessage text={t('controls.between')} values={{before: <PlayerLabel snapshot={snapshot} id={order[slot - 2]}/>, after: <PlayerLabel snapshot={snapshot} id={order[slot - 1]}/>}}/>;
    };
    return <>
        {eligiblePurchase && <div className="token-stock-summary"><span>{t('game.tokens')}</span><strong>{me.stockTokens}</strong></div>}
        {eligiblePurchase && quoteError && <p className="blocker">{t(`error.${quoteError}`)}</p>}
        {((stage === 'BIDDING' && me.misery !== null) || eligiblePurchase) && <DecisionForm onSubmit={event => { event.preventDefault(); if (!busy && (!isTokenPurchase || (validPurchase && quoted))) void submit(`${isTokenPurchase ? 'buy-tokens' : 'bid-capital'} ${bid}`); }}>
            <label htmlFor="bid">{t(isTokenPurchase ? 'controls.tokenPurchase' : 'controls.bid')}</label>
            <div className="inline-form"><NumberInput id="bid" min={snapshot.legalChoices.bidMin ?? 0} max={snapshot.legalChoices.bidMax ?? 40} step={1} value={bid} onValueChange={setBid} aria-describedby={isTokenPurchase ? 'purchase-preview' : undefined} required/>
                <DecisionAction type="submit" className="bid-action" disabled={busy || (isTokenPurchase && (!validPurchase || !quoted))}>
                    {bidSubmitLabel(snapshot)}
                </DecisionAction>
            </div>
            <p className="private-draft-status" role="status">{t(snapshot.pendingBid !== null && bid === String(snapshot.pendingBid) ? 'controls.savedPrivately' : 'controls.unsavedDraft', {amount: bid})}</p>
            {snapshot.pendingBid !== null && <p className="pending-submission">{t(isTokenPurchase ? 'controls.pendingPurchase' : 'controls.pending')}: {isTokenPurchase ? snapshot.pendingBid : `$${snapshot.pendingBid}`}</p>}
            {isTokenPurchase && <div id="purchase-preview" className="purchase-preview" aria-live="polite">
                {quoted ? <dl className="resources">
                    <div><dt>{t('controls.purchaseCost')}</dt><dd>${quoted.cost}</dd></div>
                    <div><dt>{t('controls.purchaseTokens')}</dt><dd>{quoted.tokensReceived}</dd></div>
                    <div><dt>{t('controls.remainingCash')}</dt><dd>${quoted.cashAfter}</dd></div><div><dt>{t('game.tokens')}</dt><dd>{me.stockTokens} → {quoted.stockAfter}</dd></div>
                </dl> : <p className="small">{validPurchase ? t('table.quotePending') : t('controls.purchaseRange').split('{cash}').join(String(me.cash))}</p>}
            </div>}
        </DecisionForm>}
        {capitalChoices.length > 0 && <div className="capital-choices">{capitalChoices.map(choice => <button type="button" key={choice} className="secondary" disabled={busy} aria-pressed={capital === choice} onClick={() => setCapital(choice)}><CapitalLabel capital={choice.split(' ')[1]}>{t(`capital.${choice.split(' ')[1]}`)}</CapitalLabel></button>)}<DecisionAction className="capital-confirmation" disabled={busy || !capitalChoices.includes(capital)} onClick={() => { void submit(capital); }}>{t('table.confirm')}</DecisionAction></div>}
        {capitalChoices.length > 0 && <MapPicker title={t('controls.chooseProvince')} eligible={capitalChoices.map(choice => choice.split(' ')[1])}
            selected={capital ? [capital.split(' ')[1]] : []} disabled={busy} onPick={id => setCapital(`choose-capital ${id}`)}/>}
        {stage === 'TURN_ORDER_TIE' && <div className="turn-tie">
            <p className="muted">{t('controls.provisionalOrder')}: {snapshot.game.provisionalTieOrder.length ? <PlayerLabels snapshot={snapshot} ids={snapshot.game.provisionalTieOrder} separator=" → "/> : t('controls.noPositions')}</p>
            {turnChoices.length > 0 && <div className="capital-choices">{turnChoices.map(choice => <button type="button" className="secondary" key={choice} disabled={busy} aria-pressed={position === choice} onClick={() => setPosition(choice)}>{positionLabel(Number(choice.split(' ')[1]))}</button>)}<DecisionAction className="turn-confirmation" disabled={busy || !turnChoices.includes(position)} onClick={() => { void submit(position); }}>{t('table.confirm')}</DecisionAction></div>}
        </div>}
        {canAct && <GameActions snapshot={snapshot} busy={busy} submit={submit}/>}

    </>;
}
