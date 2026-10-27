import {open} from './browser-support.mjs';
import {checkSetupEvidence} from './nextsteps.browser.mjs';

const page = await open(process.env.MAP_WEB_URL);
try {await checkSetupEvidence(page,process.env.MAP_WEB_URL);}
finally {
    page.ws.close();
    await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
}
