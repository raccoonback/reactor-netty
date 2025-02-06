/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2PingFrame;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Handler that supports connection health checks using HTTP/2 Ping Frames.
 *
 * <p>This Handler sends a ping frame at the specified interval when no frame is being read or written,
 * ensuring the connection health is monitored. If a ping ACK frame is not received within the configured interval,
 * the connection will be closed.</p>
 *
 * <p>Ping frame checking will not be performed while a read or write operation is in progress.</p>
 *
 * <p>Be cautious when setting a very short interval, as it may cause the connection to be closed,
 * even if the keep-alive setting is enabled.</p>
 *
 * <p>If no interval is specified, no ping frame checking will be performed.</p>
 *
 * @author raccoonback
 * @since 1.2.3
 */
final class Http2ConnectionLivenessHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(Http2ConnectionLivenessHandler.class);

	private ScheduledFuture<?> pingScheduler;
	private final ChannelFutureListener pingWriteListener = new PingWriteListener();
	private final Http2ConnectionEncoder encoder;
	private final long pingAckTimeoutNanos;
	private final long pingScheduleIntervalNanos;
	private final int pingAckDropThreshold;
	private int pingAckDropCount;
	private long lastSentPingData;
	private long lastReceivedPingTime;
	private long lastSendingPingTime;
	private long lastIoTime;
	private boolean isPingAckPending;

	public Http2ConnectionLivenessHandler(Http2ConnectionEncoder encoder, @Nullable Duration pingAckTimeout,
	                                      @Nullable Duration pintScheduleInterval, @Nullable Integer pingAckDropThreshold) {
		Objects.requireNonNull(encoder, "encoder");

		this.encoder = encoder;

		if (pingAckTimeout != null) {
			this.pingAckTimeoutNanos = pingAckTimeout.toNanos();
		}
		else {
			this.pingAckTimeoutNanos = 0L;
		}

		if (pintScheduleInterval != null) {
			this.pingScheduleIntervalNanos = pintScheduleInterval.toNanos();
		}
		else {
			this.pingScheduleIntervalNanos = 0L;
		}

		if (pingAckDropThreshold != null) {
			this.pingAckDropThreshold = pingAckDropThreshold;
		}
		else {
			this.pingAckDropThreshold = 0;
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		if (isPingIntervalConfigured()) {
			isPingAckPending = false;
			pingAckDropCount = 0;
			pingScheduler = ctx.executor()
					.schedule(
							new PingChecker(ctx),
							pingAckTimeoutNanos,
							NANOSECONDS
					);
		}

		ctx.fireChannelActive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		log.warn("[Http2ConnectionLivenessHandler] channelRead. data: {}", msg);
		if (msg instanceof Http2PingFrame) {
			Http2PingFrame frame = (Http2PingFrame) msg;

			log.warn("[Http2ConnectionLivenessHandler] channelRead. ping data: {}", msg);

			if (frame.ack() && frame.content() == lastSentPingData) {
				log.warn("[Http2ConnectionLivenessHandler] channelRead. ping correct data: {}", msg);


				lastReceivedPingTime = System.nanoTime();
			}
		}
		else {
			log.warn("[Http2ConnectionLivenessHandler] channelRead. other data: {}", msg);

			lastIoTime = System.nanoTime();
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		log.warn("[Http2ConnectionLivenessHandler] write. data: {}", msg);

		lastIoTime = System.nanoTime();

		ctx.write(msg, promise);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		log.warn("[Http2ConnectionLivenessHandler] channelInactive.");

		cancel();
		ctx.fireChannelInactive();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		log.warn("[Http2ConnectionLivenessHandler] exceptionCaught.");

		cancel();
		ctx.fireExceptionCaught(cause);
	}

	private boolean isPingIntervalConfigured() {
		return pingAckTimeoutNanos > 0
				&& pingScheduleIntervalNanos > 0;
	}

	private void cancel() {
		if (pingScheduler != null) {
			pingScheduler.cancel(false);
		}
	}

	private class PingChecker implements Runnable {

		private final ChannelHandlerContext ctx;

		PingChecker(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void run() {
			log.warn("[Http2ConnectionLivenessHandler][PingChecker] run start.");

			Channel channel = ctx.channel();
			if (channel == null || !channel.isOpen()) {
				log.warn("[Http2ConnectionLivenessHandler][PingChecker] channel closed.");

				return;
			}

			if (lastIoTime == 0 || isIoInProgress()) {
				log.warn(
						"[Http2ConnectionLivenessHandler][PingChecker] run io processing. isPingAckPending: {}, lastData: {}, lastIoTime: {}, lastReceivedPingTime: {}, current: {}",
						isPingAckPending,
						lastSentPingData,
						lastIoTime,
						lastReceivedPingTime,
						System.nanoTime()
				);

				isPingAckPending = false;
				pingAckDropCount = 0;
				pingScheduler = invokeNextSchedule();
				return;
			}

			if (!isPingAckPending) {
				log.warn(
						"[Http2ConnectionLivenessHandler][PingChecker] write ping. isPingAckPending: {}, lastData: {}, lastIoTime: {}, lastReceivedPingTime: {}, current: {}",
						isPingAckPending,
						lastSentPingData,
						lastIoTime,
						lastReceivedPingTime,
						System.nanoTime()
				);

				writePing(ctx);
				pingScheduler = invokeNextSchedule();
				return;
			}

			if (isOutOfTimeRange()) {
				countPingDrop();

				log.warn(
						"[Http2ConnectionLivenessHandler][PingChecker] close by last ping ack. isPingAckPending: {}, lastData: {}, lastIoTime: {}, lastReceivedPingTime: {}, current: {}, interval: {}",
						isPingAckPending,
						lastSentPingData,
						lastIoTime,
						lastReceivedPingTime,
						System.nanoTime(),
						pingAckTimeoutNanos
				);

				if (isExceedAckDropThreshold()) {
					log.warn("Closing {} channel due to delayed ping frame response (timeout: {} ns). lastReceivedPingTime: {}, current: {}", channel, pingAckTimeoutNanos, lastReceivedPingTime, System.nanoTime());

					close(channel);
					return;
				}

				log.warn("Drop ping ack frame in {} channel. (ping: {})", channel, lastSentPingData);

				writePing(ctx);
				pingScheduler = invokeNextSchedule();
				return;
			}

			log.warn(
					"[Http2ConnectionLivenessHandler][PingChecker] received ping ack. isPingAckPending: {}, lastData: {}, lastIoTime: {}, lastSendingPingTime: {}, lastReceivedPingTime: {}, current: {}",
					isPingAckPending,
					lastSentPingData,
					lastIoTime,
					lastSendingPingTime,
					lastReceivedPingTime,
					System.nanoTime()
			);

			isPingAckPending = false;
			pingAckDropCount = 0;
			pingScheduler = invokeNextSchedule();
		}

		private void writePing(ChannelHandlerContext ctx) {
			lastSentPingData = ThreadLocalRandom.current().nextLong();

			encoder.frameWriter()
					.writePing(ctx, false, lastSentPingData, ctx.newPromise())
					.addListener(pingWriteListener);
			ctx.flush();
		}

		private boolean isIoInProgress() {
			return pingAckTimeoutNanos >= (System.nanoTime() - lastIoTime);
		}

		private boolean isOutOfTimeRange() {
			log.warn("[Http2ConnectionLivenessHandler][isOutOfTimeRange] current: {}, lastSendingPingTime: {}, lastReceivedPingTime: {}, interval: {}", System.nanoTime(), lastSendingPingTime, lastReceivedPingTime, lastSendingPingTime - lastReceivedPingTime);

			return pingAckTimeoutNanos < Math.abs(lastReceivedPingTime - lastSendingPingTime);
		}

		private void countPingDrop() {
			pingAckDropCount++;
		}

		private boolean isExceedAckDropThreshold() {
			return pingAckDropCount > pingAckDropThreshold;
		}

		private ScheduledFuture<?> invokeNextSchedule() {
			return ctx.executor()
					.schedule(
							new PingChecker(ctx),
							pingScheduleIntervalNanos,
							NANOSECONDS
					);
		}

		private void close(Channel channel) {
			channel.close()
					.addListener(future -> {
						log.warn(
								"[Http2ConnectionLivenessHandler][close] close channel. isPingAckPending: {}, lastData: {}, lastIoTime: {}, lastReceivedPingTime: {}, result: {}",
								isPingAckPending,
								lastSentPingData,
								lastIoTime,
								lastReceivedPingTime,
								future.isSuccess()
						);
					});
		}
	}

	private class PingWriteListener implements ChannelFutureListener {

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			log.warn(
					"[Http2ConnectionLivenessHandler][PingWriteListener] write ping result. isPingAckPending: {}, lastData: {}, lastIoTime: {}, lastReceivedPingTime: {}, result: {}",
					isPingAckPending,
					lastSentPingData,
					lastIoTime,
					lastReceivedPingTime,
					future.isSuccess()
			);

			if (future.isSuccess()) {
				isPingAckPending = true;
				lastSendingPingTime = System.nanoTime();
			}

		}
	}
}
