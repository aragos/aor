export const serverBotProfiles = [
    'aor-coordinated-83-support-1',
    'aor-coordinated-win-support-1',
    'aor-coordinated-robust-support-1',
    'aor-broad-hard-1',
    'aor-three-player-hard-1',
] as const;

export type ServerBotProfile = typeof serverBotProfiles[number];
