import './Greeting.css';

import {useState} from 'react';
import {JSLogo} from '../JSLogo/JSLogo.tsx';
import type {AnimationEvent} from 'react';

function browserGreeting(): string {
    const userAgent = navigator.userAgent;
    const match = /Chrome|Firefox|Safari|Edge/i.exec(userAgent);
    const browser = match ? userAgent.slice(match.index).split(' ')[0] : 'Unknown';
    return `Hello, ${browser}!`;
}

export function Greeting() {
    const [isVisible, setIsVisible] = useState<boolean>(false);
    const [isAnimating, setIsAnimating] = useState<boolean>(false);

    const handleClick = () => {
        if (isVisible) {
            setIsAnimating(true);
        } else {
            setIsVisible(true);
        }
    };

    const handleAnimationEnd = (event: AnimationEvent<HTMLDivElement>) => {
        if (event.animationName === 'fadeOut') {
            setIsVisible(false);
            setIsAnimating(false);
        }
    };

    return (
        <div className="greeting-container">
            <button onClick={handleClick} className="greeting-button">
                Click me!
            </button>

            {isVisible && (
                <div className={isAnimating ? 'greeting-content fade-out' : 'greeting-content'}
                     onAnimationEnd={handleAnimationEnd}>
                    <JSLogo/>
                    <div>React: {browserGreeting()}</div>
                </div>
            )}
        </div>
    );
}
