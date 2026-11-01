const logo = new URL('./assets/favicon-ready.svg', import.meta.url).href;

export function AppLogo() {
    return <img className="app-logo" src={logo} alt="Age of Renaissance" width={40} height={40}/>;
}
