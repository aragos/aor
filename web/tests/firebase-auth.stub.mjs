// Substitute only Firebase's external authentication boundary in hosted browser checks.
const auth = {currentUser: null};
const listeners = new Set();
const state = window.firebaseTest = {calls: [], popup: null};
const user = {uid: 'google-player', email: 'player@example.com', getIdToken: async () => 'firebase-id-token'};

function signedIn() {
    auth.currentUser = user;
    for (const listener of listeners) listener(user);
    return {user};
}

state.refresh = () => {for (const listener of listeners) listener(auth.currentUser);};
state.switchUser = uid => {auth.currentUser = {...user, uid}; state.refresh();};

state.finishPopup = code => {
    const popup = state.popup;
    state.popup = null;
    if (code) popup.reject({code});
    else popup.resolve(signedIn());
};

export const browserLocalPersistence = {type: 'LOCAL'};
export class GoogleAuthProvider {
    providerId = 'google.com';
}
export function getAuth() {return auth;}
export function onIdTokenChanged(_auth, listener) {
    listeners.add(listener);
    queueMicrotask(() => listener(auth.currentUser));
    return () => listeners.delete(listener);
}
export async function setPersistence(_auth, persistence) {
    state.calls.push({method: 'setPersistence', type: persistence.type});
}
export function signInWithPopup(_auth, provider) {
    state.calls.push({method: 'signInWithPopup', provider: provider.providerId});
    return new Promise((resolve, reject) => {state.popup = {resolve, reject};});
}
export async function signInWithEmailAndPassword(_auth, email, password) {
    state.calls.push({method: 'signInWithEmailAndPassword', email, password});
    return signedIn();
}
export async function createUserWithEmailAndPassword(_auth, email, password) {
    state.calls.push({method: 'createUserWithEmailAndPassword', email, password});
    return signedIn();
}
export async function sendEmailVerification() {
    state.calls.push({method: 'sendEmailVerification'});
}
export async function sendPasswordResetEmail(_auth, email) {
    state.calls.push({method: 'sendPasswordResetEmail', email});
}
export async function signOut() {
    auth.currentUser = null;
    for (const listener of listeners) listener(null);
}
