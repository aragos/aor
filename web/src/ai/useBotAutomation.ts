import {useEffect, useRef, useState} from 'react';
import {request} from '../api';
import type {BotWork, Snapshot} from '../api';
import {startBotRunner} from './botRunner';
import type {BotRunnerStatus} from './botRunner';
import {createBotWorkerClient} from './botWorkerClient';

export function useBotAutomation(snapshot: Snapshot | null, token: string | undefined,
    humanBusy: {current: boolean}, changed: () => Promise<void>) {
    const [status, setStatus] = useState<BotRunnerStatus>('starting');
    const [operating, setOperating] = useState(false);
    const [blocked, setBlocked] = useState(false);
    const runnerId = useRef(crypto.randomUUID());
    const current = useRef({snapshot, changed});
    current.current = {snapshot, changed};
    const active = useRef<ReturnType<typeof startBotRunner> | null>(null);
    const controls = useRef<AbortController | null>(null);
    const gameId = snapshot?.gameId;
    const automation = snapshot?.automation;
    const canControl = automation?.canControl ?? false;
    const paused = automation?.paused ?? false;
    const epoch = automation?.epoch ?? 0;
    const ended = snapshot?.game.stage === 'FINISHED' || (snapshot?.status !== undefined && snapshot.status !== 'ACTIVE');
    const viewer = snapshot?.viewerId;

    useEffect(() => {
        if (!gameId || !canControl || paused || ended || operating || blocked) return;
        const path = `/api/games/${gameId}/bots`;
        const worker = createBotWorkerClient();
        const running = startBotRunner(epoch, {
            runner: (operation, expectedEpoch, signal) => request<BotWork>(`${path}/runner`, token,
                {operation, runnerId: runnerId.current, expectedEpoch}, signal),
            work: (expectedEpoch, signal) => request<BotWork>(`${path}/work?runnerId=${runnerId.current}&epoch=${expectedEpoch}`, token, undefined, signal),
            submit: (work, job, command, operationId, signal) => request<BotWork>(`${path}/commands`, token, {
                expectedRevision: work.revision, expectedSubmissionVersion: job.submissionVersion,
                epoch: work.epoch, runnerId: runnerId.current, operationId,
                seat: job.seat, decisionId: job.decisionId, command,
            }, signal),
            think: worker.think,
            changed: () => current.current.changed(),
            suspended: () => humanBusy.current,
            status: value => {
                setStatus(value);
                if (value === 'stopped' || value === 'uncertain') setBlocked(true);
            },
            uuid: () => crypto.randomUUID(),
        });
        active.current = running;
        return () => {
            running.stop(); worker.stop();
            if (active.current === running) active.current = null;
        };
    }, [gameId, viewer, token, canControl, paused, epoch, ended, operating, blocked, humanBusy]);

    useEffect(() => {active.current?.wake();}, [snapshot?.revision, snapshot?.game.activity?.decisionEntry, snapshot?.submissionVersion]);
    useEffect(() => () => {controls.current?.abort();}, [gameId, viewer, token]);

    async function control(operation: 'pause' | 'resume') {
        const live = current.current.snapshot;
        if (!live?.automation?.canControl || operating) return;
        active.current?.stop();
        setOperating(true);
        const controller = new AbortController();
        controls.current?.abort(); controls.current = controller;
        try {
            await request<BotWork>(`/api/games/${live.gameId}/bots/runner`, token, {
                operation, runnerId: runnerId.current, expectedEpoch: live.automation.epoch,
            }, controller.signal);
            if (controller.signal.aborted) return;
            await current.current.changed();
            if (controller.signal.aborted) return;
            setBlocked(false);
            setStatus(operation === 'pause' ? 'paused' : 'starting');
        } catch {
            if (!controller.signal.aborted) {setBlocked(true); setStatus('uncertain');}
        } finally {
            if (!controller.signal.aborted) setOperating(false);
            if (controls.current === controller) controls.current = null;
        }
    }
    return {
        status: ended ? 'finished' as const : paused ? 'paused' as const : status,
        operating, control,
    };
}
