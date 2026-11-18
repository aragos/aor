"""Explicit C/P/E actor and complete-bundle identities; no file discovery or phase aliases."""
from dataclasses import dataclass
from enum import Enum
import hashlib
import json
from pathlib import PurePosixPath

import card_policy
import expansion_mc
import purchase_policy as b
from token_paired_data import exact, sha


class Head(str, Enum):
    CARD = 'CARD'
    PURCHASE = 'PURCHASE'
    EXPANSION = 'EXPANSION'

    @property
    def contract(self):
        return {Head.CARD:card_policy.CONTRACT, Head.PURCHASE:b.PURCHASE_CONTRACT,
            Head.EXPANSION:expansion_mc.CONTRACT}[self]


HEADS = tuple(Head)
BUNDLE_KIND = 'cpe-paired-bundle-1'


def validate_model(model, head):
    b.require(isinstance(head, Head), 'An explicit C/P/E head is required; TOKEN cannot alias a phase.')
    return b.validate_model(model, contract=head.contract)


class CpePolicy(b.ResidualPolicy):
    def __init__(self, model, head):
        super().__init__(validate_model(model, head), head.contract)
        self.valueWeights.requires_grad_(False)
        self.valueBias.requires_grad_(False)


@dataclass(frozen=True)
class FrozenActor:
    head: Head
    raw: bytes
    sha256: str

    def __post_init__(self):
        sha(self.sha256, 'Head actor')
        b.require(type(self.raw) is bytes and hashlib.sha256(self.raw).hexdigest() == self.sha256,
            'Frozen head bytes require their original raw SHA.')
        validate_model(json.loads(self.raw), self.head)

    @property
    def model(self):
        return json.loads(self.raw)

    @property
    def dictionary_sha256(self):
        model = self.model
        return b.digest({key:model[key] for key in
            ('encoderVersion','stateFeatureNames','actionFeatureNames')})


@dataclass(frozen=True)
class Bundle:
    """Raw descriptor plus explicitly supplied actors; paths are metadata, never opened here.

    Head dictionarySha256 is semantic: the canonical complete encoder/name dictionary.
    Raw native dictionary file hashes are separately bound by each data Context.
    """
    raw: bytes
    sha256: str
    actors: tuple

    def __post_init__(self):
        sha(self.sha256, 'Complete bundle')
        b.require(type(self.raw) is bytes and hashlib.sha256(self.raw).hexdigest() == self.sha256,
            'Complete bundle bytes require their original raw SHA.')
        b.require(type(self.actors) is tuple and len(self.actors) == 3 and
            all(isinstance(actor, FrozenActor) for actor in self.actors) and
            tuple(actor.head for actor in self.actors) == HEADS,
            'A bundle requires exactly CARD/PURCHASE/EXPANSION actors in explicit head order.')
        value = json.loads(self.raw); exact(value, ('kind','heads'), 'Complete CPE bundle')
        b.require(value['kind'] == BUNDLE_KIND and set(value['heads']) == {head.value for head in HEADS},
            'Only the explicit complete three-head bundle contract is admitted.')
        for actor in self.actors:
            item = value['heads'][actor.head.value]
            exact(item, ('path','sha256','dictionarySha256'), 'Bundle head subject')
            path = item['path']
            b.require(isinstance(path,str) and path and '\\' not in path and
                '..' not in PurePosixPath(path).parts and str(PurePosixPath(path)) == path,
                'Bundle head paths must be explicit normalized provenance paths.')
            b.require(item['sha256'] == actor.sha256 and item['dictionarySha256'] == actor.dictionary_sha256,
                'Each complete-bundle head must bind its exact raw actor and semantic dictionary.')

    def actor(self, head):
        b.require(isinstance(head, Head), 'Bundle lookup requires an explicit head.')
        return self.actors[HEADS.index(head)]

    def original(self):
        """Preserve actual learned C/P actors and the separately initialized E prior."""
        for actor in self.actors:
            model = actor.model
            if actor.head is Head.EXPANSION:
                b.require(model['actorWeights'] == [0.]*64,
                    'Original scoped E must retain its untrained zero-actor-output initializer.')
            else:
                b.require(any(value != 0. for value in model['actorWeights']),
                    'Original G3 C/P must preserve its nonzero learned actor output.')
        return self
