package fyi.acmc.cogpilot

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.*

/**
 * Unit tests for SnowflakeManager.
 *
 * SnowflakeManager.sqlApi is now constructor-injected (default = real client),
 * so we can pass in a mock without reflection.
 *
 * getUserProfileById expects: result["data"][[row]] where row[0] is a JSON string
 *   like {"interests":"...","complexity":"..."}
 * getDriverNum is hardcoded to return 1 (no SQL call).
 * generateStartMessage calls sqlApi.execute once (for CORTEX.COMPLETE).
 */
class SnowflakeManagerTest {

    private lateinit var mockSqlApi: SnowflakeSqlApiClient
    private lateinit var manager: SnowflakeManager

    @Before
    fun setup() {
        mockSqlApi = mock(SnowflakeSqlApiClient::class.java)
        manager = SnowflakeManager(sqlApi = mockSqlApi)
    }

    // ------- getUserProfileById -------

    @Test
    fun testGetUserProfileById_Success() {
        runBlocking {
            // The current impl queries: SELECT profile FROM USERS WHERE user_id = '...'
            // and expects data[0][0] to be a JSON string with "interests" and "complexity" keys.
            val profileStr = JSONObject().apply {
                put("interests", "Physics,Music,Travel")
                put("complexity", "advanced")
            }.toString()

            val responseJson = JSONObject().apply {
                put("data", JSONArray().apply {
                    put(JSONArray().apply { put(profileStr) })
                })
            }
            `when`(mockSqlApi.execute(anyString())).thenReturn(responseJson)

            val profile = manager.getUserProfileById("aldan_creo")

            assertEquals("Physics,Music,Travel", profile["interests"])
            assertEquals("advanced", profile["complexity"])
        }
    }

    @Test
    fun testGetUserProfileById_NoData() {
        runBlocking {
            val responseJson = JSONObject().apply {
                put("data", JSONArray())
            }
            `when`(mockSqlApi.execute(anyString())).thenReturn(responseJson)

            val profile = manager.getUserProfileById("unknown_user")

            // Should return defaults
            assertEquals("Cognitive Science, Music, Travel", profile["interests"])
            assertEquals("intermediate", profile["complexity"])
        }
    }

    @Test
    fun testGetUserProfileById_TableNotFound() {
        runBlocking {
            val responseJson = JSONObject().apply {
                put("code", "002003")
                put("message", "Table does not exist")
            }
            `when`(mockSqlApi.execute(anyString())).thenReturn(responseJson)

            val profile = manager.getUserProfileById("aldan_creo")

            // Error path should return defaults
            assertEquals("Cognitive Science, Music, Travel", profile["interests"])
            assertEquals("intermediate", profile["complexity"])
        }
    }

    // ------- getDriverNum -------

    @Test
    fun testGetDriverNum_AlwaysReturnsOne() {
        runBlocking {
            // getDriverNum is hardcoded to return 1 - no SQL call expected
            val driverId = manager.getDriverNum("ana_campillo")
            assertEquals(1, driverId)
            verifyNoInteractions(mockSqlApi)
        }
    }

    // ------- generateStartMessage -------

    @Test
    fun testGenerateStartMessage_ReturnsMessage() {
        runBlocking {
            val startMsgResponse = JSONObject().apply {
                put("data", JSONArray().apply {
                    put(JSONArray().apply {
                        put("\"Hello, let's talk about cognitive science today!\"")
                    })
                })
            }
            `when`(mockSqlApi.execute(anyString())).thenReturn(startMsgResponse)

            val message = manager.generateStartMessage(1)

            assertNotNull(message)
            assertFalse(message.isEmpty())
            verify(mockSqlApi, atLeastOnce()).execute(anyString())
        }
    }

    @Test
    fun testGenerateStartMessage_FallsBackToHello() {
        runBlocking {
            // If data is missing, should return "Hello!"
            val emptyResponse = JSONObject().apply {
                put("data", JSONArray())
            }
            `when`(mockSqlApi.execute(anyString())).thenReturn(emptyResponse)

            val message = manager.generateStartMessage(999)

            assertEquals("Hello!", message)
        }
    }
}
