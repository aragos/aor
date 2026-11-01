import {useState} from 'react';
import {DecisionAction, DecisionForm} from '../DecisionActions';
import type {ExpansionOfferView, ProvinceDefinition, PublicCatalog, Snapshot} from '../api';
import {label} from '../labels';
import {t} from '../messages';
import {ProvinceLabel} from '../IdentityLabel';
import {TableIcon} from '../TableIcon';
import {PlayerPiece} from './MapSymbols';
import {useMapSelection} from './MapSelection';
import {ChoiceButtons} from '../DecisionControls';
import {maximumPartialPlacement, provinceOrders} from './mapOrders';

export function neighbors(connections: PublicCatalog['landBorders'], province: string): string[] {
    return connections.flatMap(edge => edge.first === province ? [edge.second] : edge.second === province ? [edge.first] : []);
}

function DestinationShipping({shipping, tokens = shipping?.required}: {
    shipping: ExpansionOfferView['shipping']; tokens?: number;
}) {
    if (!shipping || tokens === undefined) return null;
    return <p className="value-change destination-shipping">
        <span><TableIcon kind="ship"/>{t('table.destinationShipping')}</span>
        <strong aria-label={t('table.shippingProjection', {used: shipping.used, tokens,
            after: shipping.used + tokens, capacity: shipping.capacity ?? '∞'})}>
            {shipping.used} → {shipping.used + tokens} / {shipping.capacity ?? '∞'}
        </strong>
    </p>;
}

function ExpansionAvailability({offer, province}: {
    offer: ExpansionOfferView | undefined; province: ProvinceDefinition;
}) {
    if (!offer) return null;
    if (offer.primaryAction === 'INSPECT') {
        return <p className="expansion-availability muted">{t('expansion.alreadyControlled')}</p>;
    }
    if (offer.blockers.length === 0) return null;
    const blockerText = (blocker: string) => {
        if (blocker === 'INSUFFICIENT_EXPANSION_TOKENS') {
            return t('expansion.poolRequired', {required: offer.requiredTokens, available: offer.availablePool});
        }
        if (blocker === 'SHIPPING_CAPACITY_EXCEEDED' && offer.shipping) {
            return t('expansion.shippingRequired', {required: offer.shipping.required,
                used: offer.shipping.used, capacity: offer.shipping.capacity ?? '∞'});
        }
        if (blocker === 'PROVINCE_ACCESS_DENIED' && offer.missingAccess?.length) {
            return t('expansion.missingAccess', {
                requirements: offer.missingAccess.map(requirement => label(requirement, 'access')).join(', '),
            });
        }
        if (blocker === 'UNREACHABLE_PROVINCE') {
            return t(province.coastal ? 'expansion.noRoute' : 'expansion.inlandNoRoute');
        }
        return t('error.' + blocker);
    };
    return <section className="expansion-availability" aria-label={t('expansion.availability')}>
        {offer.blockers.map(blocker => <p className="blocker" data-expansion-blocker={blocker}
            key={blocker}>{blockerText(blocker)}</p>)}
    </section>;
}

export function ProvinceActions({snapshot, province, busy, submit}: {
    snapshot: Snapshot; province: ProvinceDefinition; busy: boolean;
    submit: (command: string) => Promise<boolean>;
}) {
    const [partialOpen, setPartialOpen] = useState(false);
    const {partial, setPartial} = useMapSelection();
    const catalog = snapshot.game.catalog;
    if (!catalog) return null;
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const offer = snapshot.game.expansionOffers.find(candidate => candidate.destination === province.id);
    const inspection = snapshot.game.expansionInspections?.find(candidate => candidate.destination === province.id) ?? offer;
    const primary = offer && offer.primaryAction !== 'INSPECT';
    const remainingPlacement = maximumPartialPlacement(offer);
    const requiredTokens = remainingPlacement?.tokens ?? offer?.requiredTokens;
    const establishesMarker = !!offer?.establishesMarker && !remainingPlacement;
    const uncertain = !!offer?.uncertain && !remainingPlacement;
    const available = primary && (!!remainingPlacement || (offer.blockers.length === 0 && offer.resultingPool !== null));
    const partialOffer = offer?.partialPlacements.find(choice => String(choice.tokens) === partial);
    const shippingTokens = partialOpen && partialOffer ? partialOffer.tokens
        : remainingPlacement?.tokens ?? offer?.shipping?.required;
    const command = offer?.primaryAction === 'ATTACK' && !remainingPlacement ? `attack ${province.id}` : `place ${requiredTokens} ${province.id}`;
    const commands = provinceOrders(snapshot, province.id).filter(command => !command.startsWith('reduce-marker '));
    const hasPlacement = primary && (available || offer.partialPlacements.length > 0);
    if (!hasPlacement && commands.length === 0) {
        return inspection ? <section className="province-actions" aria-label={t('table.selectedProvince')}>
            <h2><ProvinceLabel snapshot={snapshot} id={province.id}/></h2>
            <DestinationShipping shipping={inspection.shipping}/>
            <ExpansionAvailability offer={inspection} province={province}/>
        </section> : null;
    }
    return <div className="province-actions" aria-label={t('table.selectedProvince')}>
        <h2><ProvinceLabel snapshot={snapshot} id={province.id}/></h2>
        <div className="map-orders">
            {primary && hasPlacement && <>
                <div className="commitment-preview"><span><span className="commitment-quantity"><TableIcon kind="token"/><strong>{requiredTokens}</strong></span><small>{t('table.poolTokens')}</small></span><span>→</span>
                    <span><svg width="28" height="28" viewBox="-28 -28 56 56" aria-hidden="true"><PlayerPiece capital={me?.capital ?? null} fresh count={establishesMarker ? undefined : requiredTokens}/></svg>
                        <small>{establishesMarker ? t(uncertain ? 'table.uncertainMarker' : 'table.newMarker') : t(uncertain ? 'table.uncertainTokens' : 'action.place')}</small></span></div>
                {!remainingPlacement && offer.incomeBefore !== null && offer.incomeAfter !== null && <p className="value-change"><span>{t('table.income')}</span><strong>${offer.incomeBefore} → ${offer.incomeAfter}</strong></p>}
                <DestinationShipping shipping={offer.shipping} tokens={shippingTokens}/>
                {remainingPlacement && <><p className="consequence">{t('table.markerNeedsTokens', {count: offer.requiredTokens})}</p><ExpansionAvailability offer={offer} province={province}/></>}
                {!remainingPlacement && offer.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
                {!partialOpen && <DecisionAction className="primary-expansion" disabled={busy || !available} onClick={() => { if (available) void submit(command); }}>
                    {remainingPlacement ? t('table.remainingTokens') : uncertain ? t('action.attack') : province.satellite ? t('action.place') : t('table.establishMarker')} · {requiredTokens} {t('table.tokens')}
                </DecisionAction>}
                {offer.partialPlacements.length > 0 && <details className="partial-placement" onToggle={event => setPartialOpen(event.currentTarget.open)}><summary>{t('table.placeFewer')}</summary>
                    <DecisionForm onSubmit={event => { event.preventDefault(); if (partialOffer) void submit(`place ${partialOffer.tokens} ${province.id}`); }}>
                        <p>{t('controls.tokenCount')}</p>
                        <ChoiceButtons label={t('controls.tokenCount')} id="partial-count" value={partial} onChange={setPartial} disabled={busy}
                            choices={offer.partialPlacements.map(choice => ({value: String(choice.tokens), label: String(choice.tokens)}))}/>
                        {partial && !partialOffer && <p className="blocker" role="status">{t('table.invalidatedChoice')}</p>}
                        {partialOffer && <p>{t('game.pool')}: {offer.availablePool} → {partialOffer.resultingPool}</p>}
                        {partialOpen && <DecisionAction type="submit" className="partial-expansion" disabled={busy || !partialOffer}>{t('action.place')} · {partialOffer?.tokens ?? '—'} {t('table.tokens')}</DecisionAction>}
                    </DecisionForm>
                </details>}
            </>}
            {commands.length > 0 && <div className="action-buttons">{commands.map(command => <DecisionAction key={command} className="secondary"
                disabled={busy} onClick={() => { void submit(command); }}>{label(command.split(' ')[0], 'action')}</DecisionAction>)}</div>}
        </div>
    </div>;
}
