package com.skydispatch.event;

import com.skydispatch.ws.TelemetryBroadcaster;
import com.skydispatch.ws.WsMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 事件推送监听器：把领域事件转成 WebSocket 的 EVENT 消息。
 *
 * <p>{@code phase = AFTER_COMMIT} 是关键：只有事务成功提交，
 * 事件才真的"发生过"，这时候推送才不会误导前端。
 *
 * <p>{@code fallbackExecution = true} 兜住"没有事务时发布事件"的情况
 * （例如启动引导代码），否则事件会被静默丢弃，排查起来很痛苦。
 */
@Component
public class EventBroadcaster {

    private final TelemetryBroadcaster broadcaster;

    public EventBroadcaster(TelemetryBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSkyEvent(SkyEvent event) {
        broadcaster.broadcast(WsMessage.event(event));
    }
}
