package com.ruta.app.precision

import org.junit.runner.RunWith
import org.junit.runners.Suite

/**
 * Run this one class to run all three precision testers (SOL, MSH, GEO).
 * The data lands in app/build/precision-reports (see PRECISION_REPORT.md there).
 */
@RunWith(Suite::class)
@Suite.SuiteClasses(SoloFarePrecisionTest::class, SharedFarePrecisionTest::class, GeofencePrecisionTest::class)
class PrecisionSuite
