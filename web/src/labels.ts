import {t} from './messages';
import type {Snapshot} from './api';

export function playerName(snapshot: Snapshot, id: string): string {
    const player = snapshot.game.players.find(player => player.id === id);
    return player?.capital ? provinceName(player.capital) : player?.name ?? id;
}

/** Identifier formatting is only a fallback for catalog labels with no translation yet. */
export function label(id: string, prefix?: string): string {
    if (prefix === 'leader' || prefix === 'event') {
        const cardKey = `card.${id.toLowerCase().replace(/_/g, '-')}`;
        if (t(cardKey) !== cardKey) return t(cardKey);
    }
    const key = prefix ? `${prefix}.${id}` : id;
    const translated = t(key);
    return translated !== key ? translated : id.toLowerCase().replace(/[_-]/g, ' ').replace(/\b\w/g, letter => letter.toUpperCase());
}

export function advanceName(id: string): string { return label(id, 'advance'); }

export function provinceName(id: string): string { return label(id, 'province'); }

/** Purchase continuations name the next step; interruptions retain their own Pass action. */
export function purchasePassLabel(snapshot: Snapshot): string {
    switch (snapshot.prompt?.messageId) {
        case 'prompt.renaissance': return t('action.keepTurnOrder');
        case 'prompt.purchase-shipping': return t('action.continueToAdvances');
        case 'prompt.purchase-advances': return t('action.continueToStabilization');
        case 'prompt.purchase-relief': return t('action.finishPurchase');
        default: return t('action.pass');
    }
}
