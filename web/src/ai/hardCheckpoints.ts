const originalVersion = 'aor-hard-2026-11-20-1';
const marketVersion = 'aor-hard-2026-11-21-1';
const expansionVersion = 'aor-hard-2026-11-22-1';
const threePlayer = {
    url: '/assets/automata/aor-hard-2026-11-20-1/three-player-continuation-model.json',
    sha256: '7446542a3e85f3db8b31d0fda632ae0d4f1ff809188b68cfda5899841ff57624',
};
const fourAndFivePlayer = {
    url: '/assets/automata/aor-hard-2026-11-20-1/continuation-model.json',
    sha256: '956b39695f744ce835a72af6452e33d061b74479a8b13938e3b968f18ce4556e',
};
const sixPlayer = {
    url: '/assets/automata/aor-hard-2026-11-20-1/broad-model.json',
    sha256: 'a479db4b3c7f580ce9979049fe407cbde4db22bf75b76d3cc54ac6f2039ce500',
};
const fourPlayerExpansion = {
    url: '/assets/automata/aor-hard-2026-11-22-1/four-player-model.json',
    sha256: '06308b32b3ef40bf12c83f9383ed0a306b7bf475f1bb163e6c4f063e429f48eb',
};

/** Stored seat identity and table size determine both controller behavior and immutable model. */
export function hardCheckpoint(version: string, playerCount: number) {
    const market = version === marketVersion || version === expansionVersion;
    if (market || version === originalVersion) {
        switch (playerCount) {
            case 3: return {...threePlayer, market};
            case 4: return {...(version === expansionVersion ? fourPlayerExpansion : fourAndFivePlayer), market};
            case 5: return {...fourAndFivePlayer, market};
            case 6: return {...sixPlayer, market};
        }
    }
    throw new Error('This game requires an unavailable automated opponent checkpoint.');
}
