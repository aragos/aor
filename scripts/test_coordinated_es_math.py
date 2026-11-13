import unittest

import coordinated_es_math as es


def fixture(generation=1, center_value=0.):
    scope=es.Scope('a'*64,'b'*64,'c'*64)
    center=es.Center(scope,generation-1,(center_value,)*256)
    directions=es.DirectionTable(scope,generation,tuple(tuple(1. if k%2==0 else -1. for k in range(256)) for _ in range(16)))
    population=dict(kind='coordinated-es-population-1',scope=scope.record,generation=generation,
        protocolSha256='f'*64,materializationSha256='0'*64,centerSha256=es.digest(center.record),directionsSha256=es.digest(directions.record),
        calibrationSha256='d'*64,worldBankSha256='e'*64,worldIds=list(range(256)),
        members=[dict(direction=row['direction'],sign=row['sign'],path=f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/bundle.json",coordinatesSha256=es.digest(row['coordinates']),
            bundleSha256=es.digest([row['direction'],row['sign']])) for row in es.mutants(center,directions)])
    unsealed={key:value for key,value in population.items() if key not in ('protocolSha256','materializationSha256','calibrationSha256')}
    unsealed['kind']='coordinated-es-materialization-1';population['materializationSha256']=es.digest(unsealed)
    fitness=dict(kind='coordinated-es-fitness-1',populationSha256=es.digest(population),
        worldBankSha256=population['worldBankSha256'],worldIds=list(range(256)),
        members=[dict(direction=row['direction'],sign=row['sign'],bundleSha256=row['bundleSha256'],
            rewards=[.6 if row['sign']==1 else .4]*256) for row in population['members']])
    return center,directions,population,fitness


class UpdateTests(unittest.TestCase):
    def test_known_sign_uses_raw_return_means_and_fixed_divisor(self):
        center,directions,population,fitness=fixture()
        value=es.update(center,directions,population,fitness)
        self.assertAlmostEqual(value['gradient'][0],.1)
        self.assertAlmostEqual(value['gradient'][1],-.1)
        self.assertEqual(value['center']['coordinates'],[1.,-1.]*128)
        self.assertEqual(value['centerRms'],1.)

    def test_all256_worlds_contribute_to_each_member_mean(self):
        center,directions,population,fitness=fixture()
        for row in fitness['members']:row['rewards']=[0.]*255+[1. if row['sign']==1 else 0.]
        value=es.update(center,directions,population,fitness)
        self.assertEqual(value['memberMeanRewards'][:2],[1/256,0.])
        self.assertEqual(value['gradient'][0],1/512)

    def test_normalization_is_joint_not_per_head(self):
        center,directions,_,_=fixture()
        values=(1.,)*64+(2.,)*64+(0.,)*128
        directions=es.DirectionTable(center.scope,1,(values,)*16)
        result=es.normalized_step(center,directions,(.5,)*16)
        z=result['center']['coordinates']
        self.assertEqual(z[64],2*z[0])
        self.assertEqual(z[128],0.)
        self.assertAlmostEqual(es.rms(z),1.)

    def test_center_radius_projection_uses_the_prescribed_divisor(self):
        center,directions,_,_=fixture(2,4.)
        directions=es.DirectionTable(center.scope,2,((1.,)*256,)*16)
        result=es.normalized_step(center,directions,(.2,)*16)
        self.assertEqual(result['unprojectedRms'],5.)
        self.assertEqual(result['radiusDivisor'],1.25)
        self.assertEqual(result['center']['coordinates'],[4.]*256)

    def test_mutants_are_not_projected_to_center_radius(self):
        center,directions,_,_=fixture(2,4.)
        directions=es.DirectionTable(center.scope,2,((10.,)*256,)*16)
        self.assertEqual(es.rms(es.mutants(center,directions)[0]['coordinates']),14.)

    def test_zero_gradient_stops_without_next_center(self):
        center,directions,population,fitness=fixture()
        for row in fitness['members']:row['rewards']=[0.]*256
        with self.assertRaisesRegex(es.OperationalStop,'zero-gradient'):es.update(center,directions,population,fitness)

    def test_tiny_gradient_uses_exact_fixed_stop(self):
        center,directions,_,_=fixture()
        with self.assertRaisesRegex(es.OperationalStop,'zero-gradient'):
            es.normalized_step(center,directions,(2e-12,)*16)

    def test_nonfinite_return_stops(self):
        center,directions,population,fitness=fixture();fitness['members'][0]['rewards'][0]=float('nan')
        with self.assertRaisesRegex(es.OperationalStop,'nonfinite'):es.update(center,directions,population,fitness)

    def test_finite_input_overflow_stops(self):
        center,directions,_,_=fixture()
        directions=es.DirectionTable(center.scope,1,((1e308,)*256,)*16)
        with self.assertRaisesRegex(es.OperationalStop,'nonfinite'):es.normalized_step(center,directions,(1e308,)*16)

    def test_world_order_is_bound_not_just_a_set(self):
        center,directions,population,fitness=fixture();fitness['worldIds'].reverse()
        with self.assertRaisesRegex(ValueError,'common ordered world'):es.update(center,directions,population,fitness)

    def test_incomplete_member_worlds_do_not_impute_returns(self):
        center,directions,population,fitness=fixture();fitness['members'][0]['rewards'].pop()
        with self.assertRaisesRegex(ValueError,'fixed dimension'):es.update(center,directions,population,fitness)

    def test_incomplete_population_stops(self):
        center,directions,population,fitness=fixture();fitness['members'].pop()
        with self.assertRaisesRegex(ValueError,'Incomplete populations'):es.update(center,directions,population,fitness)

    def test_wrong_member_bundle_is_rejected(self):
        center,directions,population,fitness=fixture();fitness['members'][0]['bundleSha256']='f'*64
        with self.assertRaisesRegex(ValueError,'member bundle'):es.update(center,directions,population,fitness)

    def test_different_projection_scope_is_rejected(self):
        center,directions,_,_=fixture()
        wrong=es.DirectionTable(es.Scope('a'*64,'b'*64,'f'*64),1,directions.vectors)
        with self.assertRaisesRegex(ValueError,'same-scope'):es.mutants(center,wrong)

    def test_stale_generation_is_rejected(self):
        center,directions,_,_=fixture()
        with self.assertRaisesRegex(ValueError,'next fixed directions'):
            es.mutants(center,es.DirectionTable(center.scope,2,directions.vectors))

    def test_prescribed_mean_uses_same_known_sign_update(self):
        center,directions,_,_=fixture();probe=es.prescribed_mean_probe(directions)
        self.assertEqual(probe['artificialDifferences'],[2.]*16)
        self.assertEqual(probe['center']['coordinates'],[1.,-1.]*128)

    def test_prescribed_probe_zero_direction_stops_no_replacement(self):
        center,_,_,_=fixture()
        with self.assertRaisesRegex(es.OperationalStop,'zero-gradient'):
            es.prescribed_mean_probe(es.DirectionTable(center.scope,1,((0.,)*256,)*16))

    def test_immutable_direction_table_copies_caller_lists(self):
        center,directions,_,_=fixture();record=directions.record;loaded=es.DirectionTable.from_record(record)
        record['vectors'][0][0]=99.
        self.assertEqual(loaded.vectors[0][0],1.)

    def test_zero_reward_is_retained_without_special_reweighting(self):
        center,directions,population,fitness=fixture()
        for row in fitness['members']:row['rewards']=[1. if row['sign']==1 else 0.]*256
        result=es.update(center,directions,population,fitness)
        self.assertEqual(result['memberMeanRewards'],[1.,0.]*16)
        self.assertEqual(result['gradient'][0],.5)


if __name__=='__main__':unittest.main()
