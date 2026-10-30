import {useLayoutEffect, useRef, useState} from 'react';
import type {Snapshot} from './api';
import {advanceName, label} from './labels';
import {t} from './messages';
import {Commodity, CommodityAdjustments, Payouts} from './TablePanels';
import {CommodityIcon} from './map/MapSymbols';
import {useMapSelection} from './map/MapSelection';
import {finiteDecisions} from './decisionOffers';
import {CardRecurrence} from './CardRecurrence';
import {LeaderCredit, leaderCreditNote} from './LeaderCredit';
import {HelpLink} from './HelpLink';
import {useDragScroll} from './useDragScroll';

/** Reveal a card within the hand's own viewport, without moving the board or page. */
function revealCard(container: HTMLDivElement, card: Element) {
    const viewport = container.getBoundingClientRect();
    const bounds = card.getBoundingClientRect();
    if (container.scrollWidth > container.clientWidth) {
        if (bounds.left < viewport.left) {
            container.scrollLeft += bounds.left - viewport.left;
        } else if (bounds.right > viewport.right) {
            container.scrollLeft += bounds.right - viewport.right;
        }
    }
    if (container.scrollHeight > container.clientHeight) {
        if (bounds.height > viewport.height || bounds.top < viewport.top) {
            container.scrollTop += bounds.top - viewport.top;
        } else if (bounds.bottom > viewport.bottom) {
            container.scrollTop += bounds.bottom - viewport.bottom;
        }
    }
}

/** The viewer's held cards are the shared selection surface for play, discard and trade. */
export function Hand({snapshot, busy = false}: {snapshot: Snapshot; busy?: boolean}) {
    const {selectedCard, setSelectedCard, setSelected, setSelectedAdvance, setPicker, inspection,
        setInspection, selectedAction, setSelectedAction} = useMapSelection();
    const [expanded, setExpanded] = useState(false);
    const [needsExpansion, setNeedsExpansion] = useState(false);
    const hand = useRef<HTMLElement>(null);
    const measureOverflow = useRef<() => void>();
    const compactScroll = useRef(0);
    const expandedScroll = useRef(0);
    const previousExpanded = useRef(false);
    const references = snapshot.game.hand.map(card => card.reference).join('|');
    const drag = useDragScroll(`${references}|${expanded}`);
    const decisions = finiteDecisions(snapshot);
    const selectedReference = selectedCard || decisions.find(decision =>
        decision.subject.kind === 'card' && decision.command === selectedAction)?.subject.id;

    useLayoutEffect(() => {
        if (!references) setExpanded(false);
    }, [references]);

    useLayoutEffect(() => {
        const container = drag.ref.current;
        if (!container) return;
        if (previousExpanded.current !== expanded) {
            container.scrollLeft = expanded ? 0 : compactScroll.current;
            container.scrollTop = expanded ? expandedScroll.current : 0;
            previousExpanded.current = expanded;
        }
        const selected = [...container.querySelectorAll('[data-hand-card]')].find(card =>
            card.getAttribute('data-hand-card') === selectedReference);
        if (selected) revealCard(container, selected);
    }, [drag.ref, expanded, selectedReference]);

    useLayoutEffect(() => {
        const container = drag.ref.current;
        const section = hand.current;
        if (!container || !section) return;
        let width = container.clientWidth;
        let height = container.clientHeight;
        const measure = () => {
            const style = getComputedStyle(section);
            // Ignore the toggle's own width so showing it cannot manufacture persistent overflow.
            const availableWidth = section.clientWidth - parseFloat(style.paddingLeft) -
                parseFloat(style.paddingRight);
            const cards = [...container.querySelectorAll<HTMLElement>('[data-hand-card]')];
            const contentClipped = cards.some(card => card.scrollHeight > card.clientHeight + 1 ||
                card.scrollWidth > card.clientWidth + 1);
            setNeedsExpansion(cards.length > 0 && (container.scrollWidth > availableWidth + 1 ||
                container.scrollHeight > container.clientHeight + 1 || contentClipped));
            if (width === container.clientWidth && height === container.clientHeight) return;
            width = container.clientWidth;
            height = container.clientHeight;
            const focused = document.activeElement;
            const card = focused instanceof Element && container.contains(focused)
                ? focused.closest('.object-control')
                : container.querySelector('[data-hand-card][aria-pressed="true"]');
            if (card) revealCard(container, card);
        };
        measureOverflow.current = measure;
        const observer = new ResizeObserver(measure);
        observer.observe(section);
        observer.observe(container);
        const layout = container.querySelector('.hand-card-layout');
        if (layout) observer.observe(layout);
        [...container.querySelectorAll('.object-control')].forEach(card => {
            observer.observe(card);
            const button = card.querySelector('[data-hand-card]');
            if (button) observer.observe(button);
        });
        measure();
        return () => {
            measureOverflow.current = undefined;
            observer.disconnect();
        };
    }, [drag.ref, references, expanded]);

    useLayoutEffect(() => {
        measureOverflow.current?.();
    }, [snapshot.game.hand, snapshot.game.cardOffers, snapshot.game.cardInspections,
        snapshot.game.commodityPayouts]);

    function setHandExpanded(next: boolean) {
        const container = drag.ref.current;
        if (container) {
            if (expanded) expandedScroll.current = container.scrollTop;
            else compactScroll.current = container.scrollLeft;
        }
        setExpanded(next);
    }

    return <section ref={hand} className={`table-hand ${expanded ? 'hand-expanded' : ''}`}
        data-empty={snapshot.game.hand.length === 0} data-overflow={needsExpansion}
        aria-label={t('game.hand')}>
        {(expanded || needsExpansion) && <button type="button" className="secondary hand-toggle"
            aria-expanded={expanded} aria-controls="hand-cards" onClick={() => setHandExpanded(!expanded)}>
            {t(expanded ? 'hand.collapse' : 'hand.expand')}
        </button>}
        <div id="hand-cards" className="hand-cards" {...drag} onFocusCapture={event => {
            const card = event.target.closest('.object-control');
            if (card && drag.ref.current) revealCard(drag.ref.current, card);
        }}><div className="hand-card-layout">{snapshot.game.hand.map(card => {
            const offer = snapshot.game.cardOffers.find(candidate => candidate.reference === card.reference);
            const info = snapshot.game.cardInspections?.find(candidate => candidate.definition.id === card.definition.id);
            const commodities = offer?.commodities ?? info?.commodities ?? [];
            const discounts = offer?.leaderDiscounts ?? info?.leaderDiscounts ?? [];
            const [firstDiscount] = discounts;
            const sharedWrittenRecordBonus = firstDiscount && discounts.every(discount =>
                discount.writtenRecordBonus === firstDiscount.writtenRecordBonus)
                ? firstDiscount.writtenRecordBonus : null;
            const finite = decisions.find(decision =>
                decision.subject.kind === 'card' && decision.subject.id === card.reference);
            const selected = selectedReference === card.reference;
            const inspected = inspection?.kind === 'card' && inspection.id === card.reference;
            const playable = !!finite || !!offer?.playable;
            const burden = snapshot.game.burdenReferences.includes(card.reference);
            const status = finite ? t(finite.kind === 'discard' ? 'action.discard' : 'action.offerCard')
                : !playable ? t('hand.notPlayable') : null;
            return <div className="object-control" key={card.reference}>
                <button type="button" data-hand-card={card.reference} aria-pressed={selected}
                    disabled={busy && playable}
                    className={`hand-card ${card.definition.kind} ${playable ? 'playable' : ''} ${inspected ? 'is-inspected' : ''}`}
                    onClick={() => {
                        if (finite) {
                            setInspection(null);
                            setSelectedAction(finite.command);
                            setHandExpanded(false);
                            return;
                        }
                        if (!offer?.playable) {
                            setInspection({kind: 'card', id: card.reference});
                            return;
                        }
                        setSelectedAction(''); setInspection(null); setSelectedCard(card.reference);
                        setSelected(''); setSelectedAdvance('');
                        if (selectedCard !== card.reference) setPicker(null);
                        setHandExpanded(false);
                    }}>
                    <h3>{t(card.definition.messageId)}</h3>
                    {card.definition.kind === 'event' && <span className="hand-card-summary">
                        {t(`${card.definition.messageId}.summary`)}
                    </span>}
                    {commodities.map(commodity => {
                        const payout = snapshot.game.commodityPayouts.find(candidate => candidate.commodity === commodity);
                        return <span className="card-commodity" key={commodity}>
                            <Commodity commodity={commodity}/>{payout && <>
                                <CommodityAdjustments payout={payout}/><Payouts snapshot={snapshot} payout={payout}/>
                            </>}
                        </span>;
                    })}
                    {discounts.map(discount => <span className="leader-discount" key={discount.advance}>
                        <span>{advanceName(discount.advance)}</span>
                        <LeaderCredit amount={discount.effectiveDiscount}
                            writtenRecordBonus={discount.writtenRecordBonus}
                            compact={sharedWrittenRecordBonus !== null}/>
                    </span>)}
                    {sharedWrittenRecordBonus !== null && <small className="hand-leader-note">
                        {leaderCreditNote(sharedWrittenRecordBonus)}
                    </small>}
                    <span className="hand-card-badges"><CardRecurrence definition={card.definition}/>
                        {burden && <span className="burden-badge">{t('game.burden')}</span>}
                        {status && <span className="hand-card-status">{status}</span>}
                    </span>
                </button>
                <span className="card-track-links">{commodities.map(commodity =>
                    <HelpLink key={commodity} topic={`commodity-${commodity.toLowerCase()}`}
                        label={`${label(commodity, 'commodity')} · ${t('table.fullPayouts')}`} inline
                        icon={<svg viewBox="0 0 24 24" width="12" height="12" aria-hidden="true">
                            <CommodityIcon commodity={commodity}/>
                        </svg>}/>)}</span>
            </div>;
        })}{snapshot.game.hand.length === 0 && <p className="muted">{t('game.noCards')}</p>}</div></div>
    </section>;
}
