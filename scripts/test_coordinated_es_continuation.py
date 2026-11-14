import copy
import unittest

import coordinated_es_math as es
import coordinated_es_continuation as c


def fixture():
    scope=es.Scope('a'*64,'b'*64,'c'*64)
    origin=es.Center(scope,16,(.5,)*256)
    warm=c.WarmStart(scope,es.digest(origin.record),'d'*64,'e'*64,'f'*64,'1'*64,origin.coordinates)
    center=c.Center.initial(warm)
    directions=es.DirectionTable(scope,1,((1.,)*256,)*16)
    members=[dict(direction=row['direction'],sign=row['sign'],
        path=f"members/{'PLUS' if row['sign']==1 else 'MINUS'}-{row['direction']:02d}/bundle.json",
        coordinatesSha256=es.digest(row['coordinates']),bundleSha256=es.digest([row['direction'],row['sign']]))
        for row in c.mutants(center,directions)]
    raw=dict(kind=c.MATERIALIZATION_KIND,parameterSpace=scope.record,warmStartSha256=warm.sha256,step=1,
        centerSha256=es.digest(center.record),directionsSha256=es.digest(directions.record),
        worldBankSha256='2'*64,worldIds=list(range(256)),members=members)
    population=c.seal_population(raw,'3'*64,'4'*64)
    fitness=dict(kind=c.FITNESS_KIND,objective=c.OBJECTIVE,protocolSha256='3'*64,
        populationSha256=es.digest(population),worldBankSha256='2'*64,worldIds=list(range(256)),
        members=[dict(direction=row['direction'],sign=row['sign'],bundleSha256=row['bundleSha256'],
            winShares=[1.]*(128 if row['sign']==1 else 120)+[0.]*(128 if row['sign']==1 else 136)) for row in members])
    return origin,warm,center,directions,population,fitness


class WarmStartTests(unittest.TestCase):
    def test_step_zero_retains_nonzero_parent_coordinates(self):
        origin,warm,center,*_=fixture()
        warm.verify_origin(origin)
        self.assertEqual(center.coordinates,origin.coordinates)
        self.assertEqual(center.step,0)

    def test_zeroing_nonzero_warm_start_is_rejected(self):
        _,warm,*_=fixture()
        with self.assertRaisesRegex(ValueError,'never zeroed'):
            c.Center(warm,0,(0.,)*256)

    def test_original_generation_zero_still_rejects_nonzero(self):
        origin,*_=fixture()
        with self.assertRaisesRegex(ValueError,'exactly zero'):
            es.Center(origin.scope,0,origin.coordinates)

    def test_warm_start_requires_complete_parent(self):
        origin,warm,*_=fixture()
        with self.assertRaisesRegex(ValueError,'complete parent'):
            warm.verify_origin(es.Center(origin.scope,15,origin.coordinates))

    def test_warm_start_rejects_other_parent_bytes(self):
        origin,warm,*_=fixture()
        with self.assertRaisesRegex(ValueError,'complete parent'):
            warm.verify_origin(es.Center(origin.scope,16,(.6,)*256))

    def test_roundtrip_retains_explicit_origin(self):
        _,warm,center,*_=fixture()
        loaded=c.WarmStart.from_record(warm.record)
        self.assertEqual(c.Center.from_record(center.record,loaded),center)

    def test_center_rejects_different_warm_start(self):
        _,warm,center,*_=fixture()
        wrong=c.WarmStart(warm.scope,warm.origin_center_sha256,'0'*64,warm.origin_protocol_sha256,
            warm.origin_admission_sha256,warm.calibration_sha256,warm.coordinates)
        with self.assertRaisesRegex(ValueError,'exact typed origin'):
            c.Center.from_record(center.record,wrong)

    def test_later_full_continuation_can_be_an_explicit_origin(self):
        _,warm,*_=fixture()
        final=c.Center(warm,16,(1.,)*256)
        inherited=c.WarmStart(final.scope,es.digest(final.record),'5'*64,'6'*64,'7'*64,
            warm.calibration_sha256,final.coordinates)
        inherited.verify_origin(final)
        self.assertEqual(c.Center.initial(inherited).coordinates,final.coordinates)


class UpdateTests(unittest.TestCase):
    def test_win_share_ascent_uses_parent_coordinate_origin(self):
        _,_,center,directions,population,fitness=fixture()
        result=c.update(center,directions,population,fitness)
        self.assertEqual(result['center']['coordinates'],[1.5]*256)
        self.assertEqual(result['center']['step'],1)
        self.assertEqual(result['memberMeanWinShares'][:2],[.5,.46875])

    def test_reward_shaping_cannot_reverse_the_win_only_step(self):
        _,_,center,directions,population,fitness=fixture()
        result=c.update(center,directions,population,fitness)
        win_delta=.5-.46875
        old_reward_delta=.8*.5-(.8*.46875+.2)
        self.assertGreater(win_delta,0.)
        self.assertLess(old_reward_delta,0.)
        self.assertGreater(result['gradient'][0],0.)

    def test_radius_remains_global_not_warm_start_relative(self):
        _,warm,*_=fixture()
        center=c.Center(warm,1,(4.,)*256)
        directions=es.DirectionTable(center.scope,2,((1.,)*256,)*16)
        value=c.normalized_step(center,directions,(1.,)*16)
        self.assertEqual(value['unprojectedRms'],5.)
        self.assertEqual(value['center']['coordinates'],[4.]*256)

    def test_mutants_still_leave_center_radius(self):
        _,warm,*_=fixture()
        center=c.Center(warm,1,(4.,)*256)
        directions=es.DirectionTable(center.scope,2,((10.,)*256,)*16)
        self.assertEqual(es.rms(c.mutants(center,directions)[0]['coordinates']),14.)

    def test_old_reward_fitness_kind_is_rejected(self):
        _,_,center,directions,population,fitness=fixture()
        wrong=dict(fitness,kind='coordinated-es-fitness-1')
        with self.assertRaisesRegex(ValueError,'win-share units'):
            c.update(center,directions,population,wrong)

    def test_legacy_units_with_new_kind_are_rejected(self):
        _,_,center,directions,population,fitness=fixture()
        wrong=dict(fitness,objective='AUTHORITATIVE_NORMALIZED_TERMINAL')
        with self.assertRaisesRegex(ValueError,'win-share units'):
            c.update(center,directions,population,wrong)

    def test_wrong_protocol_is_rejected(self):
        _,_,center,directions,population,fitness=fixture()
        with self.assertRaisesRegex(ValueError,'win-share units'):
            c.update(center,directions,population,dict(fitness,protocolSha256='5'*64))

    def test_incomplete_world_row_is_rejected(self):
        _,_,center,directions,population,fitness=fixture()
        wrong=copy.deepcopy(fitness);wrong['members'][0]['winShares'].pop()
        with self.assertRaisesRegex(ValueError,'fixed dimension'):
            c.update(center,directions,population,wrong)

    def test_zero_gradient_is_an_operational_stop(self):
        _,_,center,directions,population,fitness=fixture()
        wrong=copy.deepcopy(fitness)
        for row in wrong['members']:row['winShares']=[.5]*256
        with self.assertRaisesRegex(es.OperationalStop,'zero-gradient'):
            c.update(center,directions,population,wrong)

    def test_stale_direction_step_is_rejected(self):
        _,_,center,directions,*_=fixture()
        with self.assertRaisesRegex(ValueError,'immediate center'):
            c.mutants(center,es.DirectionTable(center.scope,2,directions.vectors))

    def test_nonfinite_win_share_stops(self):
        _,_,center,directions,population,fitness=fixture()
        wrong=copy.deepcopy(fitness);wrong['members'][0]['winShares'][0]=float('nan')
        with self.assertRaisesRegex(es.OperationalStop,'nonfinite'):
            c.update(center,directions,population,wrong)

    def test_split_win_is_averaged_without_rank_or_score(self):
        _,_,center,directions,population,fitness=fixture()
        wrong=copy.deepcopy(fitness)
        for row in wrong['members']:row['winShares']=[1/3 if row['sign']==1 else 0.]*256
        value=c.update(center,directions,population,wrong)
        self.assertEqual(value['memberMeanWinShares'][:2],[1/3,0.])


if __name__=='__main__':unittest.main()
