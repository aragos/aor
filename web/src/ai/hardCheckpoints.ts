const originalVersion = 'aor-hard-2026-11-20-1';
const marketVersion = 'aor-hard-2026-11-21-1';
const expansionVersion = 'aor-hard-2026-11-22-1';
const transferVersion = 'aor-hard-2026-11-22-2';
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

const threePlayerExpansion = {
    url: '/assets/automata/aor-hard-2026-11-22-2/three-player-model.json',
    sha256: '353af76e3c9207ca7a93297189d3ea9d4be5c6e547cce657ef07215e3c9bb821',
};
const fivePlayerExpansion = {
    url: '/assets/automata/aor-hard-2026-11-22-2/five-player-model.json',
    sha256: 'fb6c21344940db84976161a4f252f6ea8dee36029159f4befed1c61acb721b11',
};

/** Stored seat identity and table size determine both controller behavior and immutable model. */
export function hardCheckpoint(version: string, playerCount: number) {
    const transfer = version === transferVersion;
    const expansion = transfer || version === expansionVersion;
    const market = expansion || version === marketVersion;
    if (market || version === originalVersion) {
        switch (playerCount) {
            case 3: return {...(transfer ? threePlayerExpansion : threePlayer), market};
            case 4: return {...(expansion ? fourPlayerExpansion : fourAndFivePlayer), market};
            case 5: return {...(transfer ? fivePlayerExpansion : fourAndFivePlayer), market};
            case 6: return {...sixPlayer, market};
        }
    }
    throw new Error('This game requires an unavailable automated opponent checkpoint.');
}
