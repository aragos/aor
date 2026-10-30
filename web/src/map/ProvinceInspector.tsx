import type {ExpansionOfferView, ProvinceDefinition, PublicCatalog, Snapshot} from '../api';
import {label} from '../labels';
import {t} from '../messages';
import {ProvinceLabel} from '../IdentityLabel';
import {TableIcon} from '../TableIcon';
import {PlayerPiece} from './MapSymbols';
import {useMapSelection} from './MapSelection';
import {ChoiceButtons} from '../DecisionControls';
import {provinceOrders} from './mapOrders';

export function neighbors(connections: PublicCatalog['landBorders'], province: string): string[] {
    return connections.flatMap(edge => edge.first === province ? [edge.second] : edge.second === province ? [edge.first] : []);
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
    const {partial, setPartial} = useMapSelection();
    const catalog = snapshot.game.catalog;
    if (!catalog) return null;
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const offer = snapshot.game.expansionOffers.find(candidate => candidate.destination === province.id);
    const inspection = snapshot.game.expansionInspections?.find(candidate => candidate.destination === province.id) ?? offer;
    const primary = offer && offer.primaryAction !== 'INSPECT';
    const remainingPlacement = primary && offer.resultingPool === null
        ? offer.partialPlacements.reduce<typeof offer.partialPlacements[number] | undefined>(
            (maximum, choice) => !maximum || choice.tokens > maximum.tokens ? choice : maximum, undefined)
        : undefined;
    const requiredTokens = remainingPlacement?.tokens ?? offer?.requiredTokens;
    const resultingPool = remainingPlacement?.resultingPool ?? offer?.resultingPool;
    const establishesMarker = !!offer?.establishesMarker && !remainingPlacement;
    const uncertain = !!offer?.uncertain && !remainingPlacement;
    const available = primary && (!!remainingPlacement || (offer.blockers.length === 0 && offer.resultingPool !== null));
    const partialOffer = offer?.partialPlacements.find(choice => String(choice.tokens) === partial);
    const command = offer?.primaryAction === 'ATTACK' && !remainingPlacement ? `attack ${province.id}` : `place ${requiredTokens} ${province.id}`;
    const commands = provinceOrders(snapshot, province.id);
    const hasPlacement = primary && (available || offer.partialPlacements.length > 0);
    if (!hasPlacement && commands.length === 0) {
        return inspection ? <section className="province-actions" aria-label={t('table.selectedProvince')}>
            <h2><ProvinceLabel snapshot={snapshot} id={province.id}/></h2>
            <ExpansionAvailability offer={inspection} province={province}/>
        </section> : null;
    }
    return <div className="province-actions" aria-label={t('table.selectedProvince')}>
        <div>
            <p className="eyebrow">{remainingPlacement ? t('table.remainingTokens') : primary ? uncertain ? t('table.compete') : province.satellite ? t('action.place') : t('table.establishMarker') : t('table.province')}</p>
            <h2><ProvinceLabel snapshot={snapshot} id={province.id}/></h2>
        </div>
        <div className="map-orders">
            {primary && hasPlacement && <>
                <div className="pool-change" aria-label={t('game.pool')}><span>{t('table.poolTokens')}</span><strong>{offer.availablePool} <span>→</span> {resultingPool ?? '—'}</strong></div>
                <div className="commitment-preview"><span><TableIcon kind="token"/> <strong>{requiredTokens}</strong><small>{t('table.poolTokens')}</small></span><span>→</span>
                    <span><svg width="32" height="36" viewBox="-28 -28 56 56" aria-hidden="true"><PlayerPiece capital={me?.capital ?? null} fresh count={establishesMarker ? undefined : requiredTokens}/></svg>
                        <small>{establishesMarker ? t(uncertain ? 'table.uncertainMarker' : 'table.newMarker') : t(uncertain ? 'table.uncertainTokens' : 'action.place')}</small></span></div>
                {!remainingPlacement && offer.incomeBefore !== null && offer.incomeAfter !== null && <p className="value-change"><span>{t('table.income')}</span><strong>${offer.incomeBefore} → ${offer.incomeAfter}</strong></p>}
                {uncertain && <p className="consequence">{t('table.competitionOutcome')}</p>}
                {remainingPlacement && <><p className="consequence">{t('table.markerNeedsTokens', {count: offer.requiredTokens})}</p><ExpansionAvailability offer={offer} province={province}/></>}
                {!remainingPlacement && offer.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
                <button type="button" className="primary-expansion" disabled={busy || !available} onClick={() => { if (available) void submit(command); }}>
                    {remainingPlacement ? t('table.remainingTokens') : uncertain ? t('action.attack') : province.satellite ? t('action.place') : t('table.establishMarker')} · {requiredTokens} {t('table.tokens')}
                </button>
                {offer.partialPlacements.length > 0 && <details className="partial-placement"><summary>{t('table.placeFewer')}</summary>
                    <form onSubmit={event => { event.preventDefault(); if (partialOffer) void submit(`place ${partialOffer.tokens} ${province.id}`); }}>
                        <p>{t('controls.tokenCount')}</p>
                        <ChoiceButtons label={t('controls.tokenCount')} id="partial-count" value={partial} onChange={setPartial} disabled={busy}
                            choices={offer.partialPlacements.map(choice => ({value: String(choice.tokens), label: String(choice.tokens)}))}/>
                        {partial && !partialOffer && <p className="blocker" role="status">{t('table.invalidatedChoice')}</p>}
                        {partialOffer && <p>{t('game.pool')}: {offer.availablePool} → {partialOffer.resultingPool}</p>}
                        <button disabled={busy || !partialOffer}>{t('action.place')}</button>
                    </form>
                </details>}
                <details><summary>{t('table.placementDetails')}</summary>
                    {offer.shipping && <dl className="detail-list"><div><dt>{t('table.destinationShipping')}</dt><dd>{offer.shipping.used} / {offer.shipping.capacity ?? '∞'}</dd></div><div><dt>{t('table.shippingRequired')}</dt><dd>{remainingPlacement?.tokens ?? offer.shipping.required}</dd></div></dl>}
                    <p>{t('game.stockMarkers')}: {me?.stockMarkers}</p>
                </details>
            </>}
            <div className="action-buttons">{commands.filter(command => !command.startsWith('reduce-marker ')).map(command => <button type="button" key={command} className="secondary"
                disabled={busy} onClick={() => { void submit(command); }}>{label(command.split(' ')[0], 'action')}</button>)}</div>
            {commands.some(command => command.startsWith('reduce-marker ')) && <details className="marker-reduction">
                <summary>{t('action.reduceMarker')}</summary>
                {commands.filter(command => command.startsWith('reduce-marker ')).map(command => <button type="button" key={command}
                    className="secondary" disabled={busy} onClick={() => { void submit(command); }}>{t('table.confirm')}</button>)}
            </details>}

        </div>
    </div>;
}
