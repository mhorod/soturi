package gps.tracker;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import soturi.common.Jackson;
import soturi.model.EnemyId;
import soturi.model.messages_to_server.AttackEnemy;
import soturi.model.messages_to_server.MessageToServer;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void serializationCycle() throws Throwable {
        ObjectMapper normalMapper = Jackson.mapper;
        MessageToServer message = new AttackEnemy(new EnemyId(42));
        String serial = normalMapper.writeValueAsString(message);
        MessageToServer deserial = normalMapper.readValue(serial, MessageToServer.class);
        assertEquals(message, deserial);
    }
}