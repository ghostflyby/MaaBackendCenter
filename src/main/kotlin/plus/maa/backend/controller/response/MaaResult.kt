package plus.maa.backend.controller.response

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * @author AnselYuki
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaaResult<out T>(val statusCode: Int, val message: String?, val data: T?) {
    companion object {
        fun <T> success(data: T): MaaResult<T> {
            return success(null, data)
        }

        fun success(): MaaResult<Unit> {
            return success(null, Unit)
        }

        fun <T> success(msg: String?, data: T?): MaaResult<T> {
            return MaaResult(200, msg, data)
        }

        fun fail(code: Int, msg: String?): MaaResult<Nothing> {
            return MaaResult(code, msg, null)
        }

    }
}