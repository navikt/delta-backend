package no.nav.delta.email

import com.microsoft.graph.models.odataerrors.InnerError
import com.microsoft.graph.models.odataerrors.MainError
import com.microsoft.graph.models.odataerrors.ODataError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GraphErrorTest {
    @Test
    fun `odata errors are described with status, code, message and request id`() {
        val error = ODataError().apply {
            error = MainError().apply {
                code = "ErrorAccessDenied"
                message = "Access is denied."
                innerError = InnerError().apply { requestId = "req-1" }
            }
        }

        assertEquals(
            "status=0 code=ErrorAccessDenied message=Access is denied. requestId=req-1",
            describeGraphError(error),
        )
    }
}
