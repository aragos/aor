"""Explicit target contracts; omitted objectives preserve the original paired reward."""
MIXED='paired-mixed-reward-1'
WIN='paired-win-share-1'
VERSIONS=(MIXED,WIN)


def validate(value):
    if value not in VERSIONS:
        raise ValueError('Use an explicit supported paired TOKEN objective version.')
    return value
