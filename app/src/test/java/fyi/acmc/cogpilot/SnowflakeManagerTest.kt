package fyi.acmc.cogpilot

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.*

class SnowflakeManagerTest {

    @Mock
    private lateinit var mockSqlApi: SnowflakeSqlApiClient
    
    private lateinit var manager: SnowflakeManager

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        manager = SnowflakeManager()
        // use reflection to inject mock
        val field = SnowflakeManager::class.java.getDeclaredField("sqlApi")
        field.isAccessible = true
        field.set(manager, mockSqlApi)
    }

    @Test
    fun testGetUserProfileById_Success() = runBlocking {
        // arrange
        val responseJson = JSONObject().apply {
            val data = JSONArray().apply {
                put(JSONArray().apply {
                    put("Physics,Music,Travel")
                    put("advanced")
                })
            }
            put("data", data)
        }
        `when`(mockSqlApi.execute(any<String>())).thenReturn(responseJson)

        // act
        val profile = manager.getUserProfileById("aldan_creo")

        // assert
        assertEquals("Physics,Music,Travel", profile["interests"])
        assertEquals("advanced", profile["complexity"])
        verify(mockSqlApi, times(1)).execute(any<String>())
    }

    @Test
    fun testGetUserProfileById_NoData() = runBlocking {
        // arrange
        val responseJson = JSONObject().apply {
            put("data", JSONArray())
        }
        `when`(mockSqlApi.execute(any<String>())).thenReturn(responseJson)

        // act
        val profile = manager.getUserProfileById("unknown_user")

        // assert - should return defaults
        assertEquals("Cognitive Science, Music, Travel", profile["interests"])
        assertEquals("intermediate", profile["complexity"])
    }

    @Test
    fun testGetDriverNum_Success() = runBlocking {
        // arrange
        val responseJson = JSONObject().apply {
            val data = JSONArray().apply {
                put(JSONArray().apply {
                    put(2)
                })
            }
            put("data", data)
        }
        `when`(mockSqlApi.execute(any<String>())).thenReturn(responseJson)

        // act
        val driverId = manager.getDriverNum("ana_campillo")

        // assert
        assertEquals(2, driverId)
    }

    @Test
    fun testGetDriverNum_DefaultToOne() = runBlocking {
        // arrange
        val responseJson = JSONObject().apply {
            put("data", JSONArray())
        }
        `when`(mockSqlApi.execute(any<String>())).thenReturn(responseJson)

        // act
        val driverId = manager.getDriverNum("unknown")

        // assert - should default to 1
        assertEquals(1, driverId)
    }

    @Test
    fun testGenerateStartMessage_WithProfile() = runBlocking {
        // arrange - mock getDriverNum call
        val driverNumResponse = JSONObject().apply {
            val data = JSONArray().apply {
                put(JSONArray().apply {
                    put("aldan_creo")
                })
            }
            put("data", data)
        }

        val profileResponse = JSONObject().apply {
            val data = JSONArray().apply {
                put(JSONArray().apply {
                    put("Physics,Music")
                    put("advanced")
                })
            }
            put("data", data)
        }

        val startMsgResponse = JSONObject().apply {
            val data = JSONArray().apply {
                put(JSONArray().apply {
                    put("\"Hello, let's talk about physics and music today!\"")
                })
            }
            put("data", data)
        }

        `when`(mockSqlApi.execute(any())).thenReturn(driverNumResponse)
            .thenReturn(profileResponse)
            .thenReturn(startMsgResponse)

        // act
        val message = manager.generateStartMessage(1)

        // assert
        assertNotNull(message)
        assertFalse(message.isEmpty())
        verify(mockSqlApi, atLeastOnce()).execute(any<String>())
    }

    @Test
    fun testGenerateStartMessage_EmptyProfile() = runBlocking {
        // arrange - simulate no profile found
        val driverNumResponse = JSONObject().apply {
            put("data", JSONArray())
        }

        val startMsgResponse = JSONObject().apply {
            val data = JSONArray().apply {
                put(JSONArray().apply {
                    put("\"Hello there!\"")
                })
            }
            put("data", data)
        }

        `when`(mockSqlApi.execute(any())).thenReturn(driverNumResponse)
            .thenReturn(startMsgResponse)

        // act
        val message = manager.generateStartMessage(999)

        // assert - should still return non-empty message
        assertNotNull(message)
        assertFalse(message.isEmpty())
    }
}
