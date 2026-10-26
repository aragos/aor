import {useEffect, useRef, useState} from 'react';
import type {Snapshot} from './api';
import {t} from './messages';
import {playerName} from './labels';
import {GameActions} from './GameActions';
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
    const [preset, setPreset] = useState('');
    const [options, setOptions] = useState<Record<string, string>>({});
    const [rebuiltMarkerBonus, setRebuiltMarkerBonus] = useState(snapshot.game.rebuiltMarkerBonus);
    const previousRebuiltMarkerBonus = useRef(snapshot.game.rebuiltMarkerBonus);
    const previousBid = useRef(snapshot.pendingBid);
    const previousStage = useRef(snapshot.game.stage);
    useEffect(() => {
        const previous = previousRebuiltMarkerBonus.current;
        setRebuiltMarkerBonus(current => current === previous ? snapshot.game.rebuiltMarkerBonus : current);
        previousRebuiltMarkerBonus.current = snapshot.game.rebuiltMarkerBonus;
    }, [snapshot.game.rebuiltMarkerBonus]);
    useEffect(() => {
        if (previousStage.current !== snapshot.game.stage) {
            setBid(String(snapshot.pendingBid ?? 0));
            previousStage.current = snapshot.game.stage;
            previousBid.current = snapshot.pendingBid;
            return;
        }
        const previous = String(previousBid.current ?? 0);
        setBid(current => current === previous ? String(snapshot.pendingBid ?? 0) : current);
        previousBid.current = snapshot.pendingBid;
    }, [snapshot.pendingBid, snapshot.game.stage]);
    const stage = snapshot.game.stage;
    const capitalChoices = snapshot.legalChoices.commands.filter(choice => choice.startsWith('choose-capital '));
    const turnChoices = snapshot.legalChoices.commands.filter(choice => choice.startsWith('choose-turn-position '));
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const nameFor = (playerId: string) => playerName(snapshot, playerId);
    const isTokenPurchase = stage === 'TOKEN_PURCHASE';
    const eligiblePurchase = isTokenPurchase && me.misery !== null;
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
        if (slot === 1) return t('controls.before').replace('{name}', nameFor(order[0]));
        if (slot > order.length) return t('controls.after').replace('{name}', nameFor(order[order.length - 1]));
        return t('controls.between').replace('{before}', nameFor(order[slot - 2])).replace('{after}', nameFor(order[slot - 1]));
    };
    return <>
        {eligiblePurchase && <div className="token-stock-summary"><span>{t('game.tokens')}</span><strong>{me.stockTokens}</strong></div>}
        {eligiblePurchase && quoteError && <p className="blocker">{t(`error.${quoteError}`)}</p>}
        {stage === 'CONFIGURATION' && snapshot.viewerId === snapshot.creatorId && <>
            <div className="rule-options">
                <label htmlFor="rule-preset">{t('controls.preset')}</label>
                <select id="rule-preset" value={preset} disabled={busy} onChange={event => setPreset(event.target.value)}>
                    <option value="">{t('controls.choosePreset')}</option>
                    {['ah1996', 'second-edition', 'tournament'].map(value => <option key={value} value={value}>{t(`preset.${value}`)}</option>)}
                </select>
                <button className="secondary" disabled={busy || !preset} onClick={() => { void submit(`set-option preset ${preset}`); }}>{t('table.applyPreset')}</button>
                {snapshot.game.ruleOptions.map(option => <label key={option.key}>
                    {t(`option.${option.key}`)}
                    <select value={options[option.key] ?? option.value} disabled={busy} onChange={event => setOptions(current => ({...current, [option.key]: event.target.value}))}>
                        {option.choices.map(value => <option key={value} value={value}>{t(`optionValue.${value}`)}</option>)}
                    </select>
                    <button className="secondary" disabled={busy || !options[option.key] || options[option.key] === option.value} onClick={() => { void submit(`set-option ${option.key} ${options[option.key]}`); }}>{t('table.apply')}</button>
                </label>)}
            </div>
            <form onSubmit={event => { event.preventDefault(); void submit(`set-option rebuilt-marker-bonus ${rebuiltMarkerBonus.toLowerCase()}`); }}>
                <label htmlFor="setup-rebuilt-marker-bonus">{t('controls.rebuiltMarkerBonus')}</label>

                <div className="inline-form">
                    <select id="setup-rebuilt-marker-bonus" value={rebuiltMarkerBonus} onChange={event => { const value = event.target.value; if (value === 'INCLUDE' || value === 'EXCLUDE') setRebuiltMarkerBonus(value); }}>
                        {(['EXCLUDE', 'INCLUDE'] as const).map(rule => <option key={rule} value={rule}>{t(`rebuiltMarkerBonus.option.${rule}`)}</option>)}
                    </select>
                    <button className="secondary" disabled={busy}>{t('controls.saveRebuiltMarkerBonus')}</button>
                </div>
            </form>
            <button disabled={busy || rebuiltMarkerBonus !== snapshot.game.rebuiltMarkerBonus} onClick={() => { void submit('start'); }}>{t('controls.start')}</button>
        </>}
        {(stage === 'BIDDING' || isTokenPurchase) && me.misery !== null && <form onSubmit={event => { event.preventDefault(); if (!busy && (!isTokenPurchase || (validPurchase && quoted))) void submit(`${isTokenPurchase ? 'buy-tokens' : 'bid-capital'} ${bid}`); }}>
            <label htmlFor="bid">{t(isTokenPurchase ? 'controls.tokenPurchase' : 'controls.bid')}</label>
            <div className="inline-form"><input id="bid" type="number" min={snapshot.legalChoices.bidMin ?? 0} max={snapshot.legalChoices.bidMax ?? 40} step={1} value={bid} onChange={event => setBid(event.target.value)} aria-describedby={isTokenPurchase ? 'purchase-preview' : undefined} required/>
                <button disabled={busy || (isTokenPurchase && (!validPurchase || !quoted))}>
                    {bidSubmitLabel(snapshot)}
                </button>
            </div>
            {snapshot.pendingBid !== null && <p className="sealed">{t(isTokenPurchase ? 'controls.pendingPurchase' : 'controls.pending')}: {isTokenPurchase ? snapshot.pendingBid : `$${snapshot.pendingBid}`}</p>}

            {isTokenPurchase && <div id="purchase-preview" className="purchase-preview" aria-live="polite">
                {quoted ? <dl className="resources">
                    <div><dt>{t('controls.purchaseCost')}</dt><dd>${quoted.cost}</dd></div>
                    <div><dt>{t('controls.purchaseTokens')}</dt><dd>{quoted.tokensReceived}</dd></div>
                    <div><dt>{t('controls.remainingCash')}</dt><dd>${quoted.cashAfter}</dd></div><div><dt>{t('game.tokens')}</dt><dd>{me.stockTokens} → {quoted.stockAfter}</dd></div>
                </dl> : <p className="small">{validPurchase ? t('table.quotePending') : t('controls.purchaseRange').split('{cash}').join(String(me.cash))}</p>}
            </div>}
        </form>}
        {capitalChoices.length > 0 && <div className="capital-choices">{capitalChoices.map(choice => <button type="button" key={choice} className="secondary" disabled={busy} aria-pressed={capital === choice} onClick={() => setCapital(choice)}>{t(`capital.${choice.split(' ')[1]}`)}</button>)}<button disabled={busy || !capitalChoices.includes(capital)} onClick={() => { void submit(capital); }}>{t('table.confirm')}</button></div>}
        {stage === 'TURN_ORDER_TIE' && <div className="turn-tie">
            <p className="muted">{t('controls.provisionalOrder')}: {snapshot.game.provisionalTieOrder.length ? snapshot.game.provisionalTieOrder.map(nameFor).join(' → ') : t('controls.noPositions')}</p>
            {turnChoices.length > 0 && <div className="capital-choices">{turnChoices.map(choice => <button type="button" className="secondary" key={choice} disabled={busy} aria-pressed={position === choice} onClick={() => setPosition(choice)}>{positionLabel(Number(choice.split(' ')[1]))}</button>)}<button disabled={busy || !turnChoices.includes(position)} onClick={() => { void submit(position); }}>{t('table.confirm')}</button></div>}
        </div>}
        {(canAct || stage === 'SETUP_RETURN') && <GameActions snapshot={snapshot} busy={busy} submit={submit}/>}

    </>;
}
