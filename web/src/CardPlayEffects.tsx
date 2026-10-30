import type {CardChoiceView, CardDefinition, Snapshot} from './api';
import {CardEventText} from './CardEventText';
import {LabelMessage, PlayerLabel, ProvinceLabel} from './IdentityLabel';
import {label} from './labels';
import {t} from './messages';

function selectedCommodity(choice: CardChoiceView | undefined, commodities: string[]): string | null {
    if (!choice) return null;
    if (choice.kind === 'COMMODITY') return choice.value;
    return commodities.length === 1 ? commodities[0] : null;
}

/** Explain the chosen play without resolving rules or inventing unprojected outcomes. */
export function CardPlayEffects({snapshot, definition, choice, commodities, targets, protection}: {
    snapshot: Snapshot;
    definition: CardDefinition;
    choice: CardChoiceView | undefined;
    commodities: string[];
    targets: string[];
    protection: number | null;
}) {
    const commodity = definition.kind === 'commodity' ? selectedCommodity(choice, commodities) : null;
    return <section className="card-play-effects" aria-label={t('card.playEffects')}>
        <CardEventText definition={definition}/>
        {definition.kind === 'commodity' && <p>{commodity
            ? <LabelMessage text={t('card.commodityEffect')} values={{commodity: label(commodity.toUpperCase(), 'commodity')}}/>
            : t('card.chooseCommodityEffect')}</p>}
        {commodity && choice?.cashChanges?.length === 0 && <p>{t('card.noCommodityPayouts')}</p>}
        {choice?.kind === 'PLAYER' && choice.value && <p>{t('card.target')}: <PlayerLabel snapshot={snapshot} id={choice.value}/></p>}
        {choice?.kind === 'PROVINCE' && choice.value && <p>{t('card.target')}: <ProvinceLabel snapshot={snapshot} id={choice.value}/></p>}
        {choice?.kind === 'AREA' && choice.value && <p>{t('card.affectedArea')}: {choice.value.toUpperCase()}</p>}
        {choice?.kind === 'CATEGORY' && <p>{t('card.forbiddenPurchases')}: {choice.value ? label(choice.value.toUpperCase(), 'category') : t('table.noCategory')}</p>}
        {targets.length > 0 && <p>{t('card.targets')}: {targets.map((id, index) => <span key={id}>
            {index > 0 && ', '}<ProvinceLabel snapshot={snapshot} id={id}/>
        </span>)}</p>}
        {protection !== null && protection > 0 && <p>{t('game.protection')}: ${protection}</p>}
    </section>;
}
