"""Versioned legal token query sets; observations always retain the ordinary baseline."""
from enum import Enum

from automata_experiments import require
from token_order_fixed_reference import expected_reference_index


class ProposalProfile(Enum):
    ORDINARY = 'token-order-proposals-1'
    REFERENCE_NEIGHBORS = 'token-order-reference-neighbor-proposals-1'


def offered_amounts(observation):
    commands = [action['command'] for action in observation['actions']]
    amounts = [int(command.removeprefix('buy-tokens ')) for command in commands]
    require(all(command == f'buy-tokens {amount}' for command, amount in zip(commands, amounts)),
            'Token observations require canonical signed purchase commands.')
    return commands, amounts


def legacy_indices(observation):
    """Original six target quotes, including exact nearest-offer tie ordering."""
    commands, amounts = offered_amounts(observation)
    amount = amounts[observation['baselineIndex']]
    cash = round(observation['stateFeatures'].get('role.0.cash', 0.) * 200)
    stock = round(observation['stateFeatures'].get('role.0.stock', 0.) * 36)
    maximum, positive = min(cash, stock), max(amount, 0)
    targets = (amount, 0, -min(4, cash), positive//2, min(maximum, positive+8), maximum)
    proposed = [min(range(len(amounts)), key=lambda index:
                    (abs(amounts[index]-target), amounts[index], commands[index]))
                for target in targets]
    return list(dict.fromkeys(proposed))


def ordinary_indices(observation):
    """Original order and arithmetic, shared by old loaders and unchanged new contexts."""
    _, amounts = offered_amounts(observation)
    baseline = observation['baselineIndex']
    amount = amounts[baseline]
    neighbors = [amounts.index(value) for value in (amount-1, amount+1) if value in amounts]
    return list(dict.fromkeys([baseline, *neighbors, *legacy_indices(observation)]))


def reference_neighbor_indices(observation):
    ordinary = observation['baselineIndex']
    reference = expected_reference_index(observation)
    if reference == ordinary:
        return ordinary_indices(observation)
    _, amounts = offered_amounts(observation)
    amount = amounts[reference]
    neighbors = [amounts.index(value) for value in (amount-1, amount+1) if value in amounts]
    result = list(dict.fromkeys([ordinary, reference, *neighbors, *legacy_indices(observation)]))
    require(len(result) <= 8 and ordinary in result and reference in result,
            'Reference-neighbor queries must retain both references within eight legal offers.')
    return result


def indices(observation, profile):
    require(isinstance(profile, ProposalProfile), 'Token proposal ownership must be explicit.')
    return (ordinary_indices(observation) if profile is ProposalProfile.ORDINARY
            else reference_neighbor_indices(observation))
