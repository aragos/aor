import {createContext, useContext, useId, useLayoutEffect, useState} from 'react';
import type {ButtonHTMLAttributes, FormHTMLAttributes, ReactNode} from 'react';
import {createPortal} from 'react-dom';
import {t} from './messages';

const ActionTarget = createContext<HTMLDivElement | null | undefined>(undefined);
const ActionForm = createContext<string | undefined>(undefined);
const PlanningContext = createContext(false);

export function PlanningActions({children, active}: {children: ReactNode; active: boolean}) {
    return <PlanningContext.Provider value={active}>{children}</PlanningContext.Provider>;
}

/** Keep final actions in a fixed footer; its portal target precedes Pass in keyboard order. */
export function DecisionActions({children, pass}: {children: ReactNode; pass?: ReactNode}) {
    const [target, setTarget] = useState<HTMLDivElement | null>(null);
    useLayoutEffect(() => {
        const footer = target?.parentElement;
        if (!footer) return;
        const group = () => {
            const buttons = [...footer.querySelectorAll('button')];
            footer.dataset.layout = buttons.length === 2 && buttons.some(button => button.classList.contains('pass-action'))
                ? 'paired' : 'separate';
            footer.dataset.actionCount = String(buttons.length);
        };
        const observer = new MutationObserver(group);
        observer.observe(footer, {childList: true, subtree: true});
        group();
        return () => observer.disconnect();
    }, [target]);
    return <ActionTarget.Provider value={target}>
        {children}
        <div id="decision-footer" className="decision-actions" role="group" aria-label={t('table.actions')}>
            <div ref={setTarget} className="decision-action-target"/>
            {pass}
        </div>
    </ActionTarget.Provider>;
}

/** Native form ownership preserves Enter, input validation and submission from the footer. */
export function DecisionForm({id, ...props}: FormHTMLAttributes<HTMLFormElement>) {
    const generated = useId();
    const formId = id ?? `decision-form-${generated}`;
    return <ActionForm.Provider value={formId}><form {...props} id={formId}/></ActionForm.Provider>;
}

export function DecisionAction({type = 'button', form, notice, ...props}: ButtonHTMLAttributes<HTMLButtonElement> & {
    notice?: ReactNode;
}) {
    const planning = useContext(PlanningContext);
    const target = useContext(ActionTarget);
    const formId = useContext(ActionForm);
    const button = <button {...props} type={type} form={form ?? formId}>{planning && <>{t('plan.add')} · </>}{props.children}</button>;
    const content = <>{notice}{button}</>;
    return target === undefined ? content : target && createPortal(content, target);
}
