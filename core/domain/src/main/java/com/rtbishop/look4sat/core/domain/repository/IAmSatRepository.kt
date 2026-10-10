package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmission
import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmitResult
import com.rtbishop.look4sat.core.domain.model.SatStatusPage

/** AMSAT satellite status data source */
interface IAmSatRepository {
    /** Fetch and parse the AMSAT status page; null on failure */
    suspend fun fetchStatus(): SatStatusPage?

    /**
     * The status page for filtering, where a few minutes of staleness only costs a satellite its
     * slot in a list: implementations may serve a recent copy instead of fetching again.
     */
    suspend fun recentStatus(): SatStatusPage? = fetchStatus()

    /** Submit a public AMSAT satellite status report. */
    suspend fun submitReport(submission: AmSatReportSubmission): AmSatReportSubmitResult
}
