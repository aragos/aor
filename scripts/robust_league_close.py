"""External post-audit physical closure; retained numerical and model bytes stay immutable."""
import argparse
from pathlib import Path

import robust_token_campaign as c
import robust_token_close as previous


def close(protocol,profiles,output):
    result=previous.close(protocol,profiles,output)
    output=Path(output)
    c.require(result['hosts']['DESKTOP']['originalNumericalAuditSha256']==
        c.sha(output/'numerical-physical-review/proof.json'),
        'The original Desktop numerical audit and every retained byte must still match its sealed local receipt.')
    result['kind']='robust-league-closed-metadata-review-1'
    result['allOriginalNumericalArtifactsRehashed']=True
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('protocol','desktop-profile','mac-profile','output','receipt'):
        parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    c.write(args.receipt,close(args.protocol,{'DESKTOP':args.desktop_profile,'MAC':args.mac_profile},args.output))
