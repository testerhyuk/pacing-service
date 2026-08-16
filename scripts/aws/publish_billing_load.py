#!/usr/bin/env python3

import argparse
import base64
import datetime
import json
import socket
import subprocess
import sys
import time


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bootstrap-server", required=True)
    parser.add_argument("--reservation-prefix", required=True)
    parser.add_argument("--event-prefix", required=True)
    parser.add_argument("--event-count", type=int, required=True)
    parser.add_argument("--rate", type=int, required=True)
    parser.add_argument("--amount", type=int, required=True)
    parser.add_argument("--topic", default="billing.events.v1")
    parser.add_argument("--redis-host")
    parser.add_argument("--redis-port", type=int, default=6379)
    parser.add_argument("--redis-password")
    parser.add_argument("--redis-key-prefix", default="pacing")
    parser.add_argument("--campaign-id")
    parser.add_argument(
        "--reservation-wait-timeout",
        type=int,
        default=60,
    )
    return parser.parse_args()


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat().replace(
        "+00:00", "Z"
    )


def utc_after_epoch_millis(epoch_millis):
    now_millis = int(time.time() * 1000)
    occurred_at_millis = max(now_millis, epoch_millis + 1)
    return datetime.datetime.fromtimestamp(
        occurred_at_millis / 1000,
        datetime.timezone.utc,
    ).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def encode_redis_key_part(value):
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode(
        "ascii"
    ).rstrip("=")


class RedisClient:
    def __init__(self, host, port, password):
        self.host = host
        self.port = port
        self.password = password
        self.connection = None
        self.reader = None
        self.connect()

    def connect(self):
        self.close()
        self.connection = socket.create_connection(
            (self.host, self.port),
            timeout=3,
        )
        self.connection.settimeout(3)
        self.reader = self.connection.makefile("rb")
        if self.password:
            self.execute("AUTH", self.password)

    def close(self):
        if self.reader is not None:
            self.reader.close()
            self.reader = None
        if self.connection is not None:
            self.connection.close()
            self.connection = None

    def execute(self, *arguments):
        encoded_arguments = [
            str(argument).encode("utf-8")
            for argument in arguments
        ]
        payload = [f"*{len(encoded_arguments)}\r\n".encode("ascii")]
        for argument in encoded_arguments:
            payload.append(f"${len(argument)}\r\n".encode("ascii"))
            payload.append(argument)
            payload.append(b"\r\n")

        self.connection.sendall(b"".join(payload))
        return self.read_response()

    def read_response(self):
        response_type = self.reader.read(1)
        if not response_type:
            raise ConnectionError("Redis connection was closed")

        line = self.reader.readline()
        if not line.endswith(b"\r\n"):
            raise ConnectionError("Invalid Redis response")
        value = line[:-2]

        if response_type == b"+":
            return value.decode("utf-8")
        if response_type == b":":
            return int(value)
        if response_type == b"-":
            raise RuntimeError(
                f"Redis command failed: {value.decode('utf-8')}"
            )
        if response_type == b"$":
            length = int(value)
            if length == -1:
                return None
            data = self.reader.read(length)
            terminator = self.reader.read(2)
            if terminator != b"\r\n":
                raise ConnectionError("Invalid Redis bulk response")
            return data.decode("utf-8")

        raise ConnectionError(
            f"Unsupported Redis response type: {response_type!r}"
        )

    def hget_with_reconnect(self, key, field):
        try:
            return self.execute("HGET", key, field)
        except (OSError, ConnectionError):
            self.connect()
            return self.execute("HGET", key, field)


def reservation_key(key_prefix, campaign_id, reservation_id):
    return (
        f"{key_prefix}:reservation:"
        f"{{{encode_redis_key_part(campaign_id)}}}:"
        f"{encode_redis_key_part(reservation_id)}"
    )


def wait_for_reservation(
    redis_client,
    key_prefix,
    campaign_id,
    reservation_id,
    timeout_seconds,
):
    key = reservation_key(
        key_prefix,
        campaign_id,
        reservation_id,
    )
    deadline = time.monotonic() + timeout_seconds
    waited = False

    while time.monotonic() < deadline:
        reserved_at = redis_client.hget_with_reconnect(
            key,
            "reservedAtEpochMillis",
        )
        if reserved_at is not None:
            return int(reserved_at), waited
        waited = True
        time.sleep(0.025)

    raise TimeoutError(
        "Reservation was not created before billing timeout: "
        f"{reservation_id}"
    )


def main():
    args = parse_args()
    if args.event_count <= 0 or args.rate <= 0 or args.amount <= 0:
        raise ValueError("event-count, rate and amount must be positive")

    redis_options = (
        args.redis_host,
        args.redis_password,
        args.campaign_id,
    )
    redis_gating_enabled = all(redis_options)
    if any(redis_options) and not redis_gating_enabled:
        raise ValueError(
            "redis-host, redis-password and campaign-id must be "
            "provided together"
        )
    if args.reservation_wait_timeout <= 0:
        raise ValueError("reservation-wait-timeout must be positive")

    redis_client = None
    if redis_gating_enabled:
        redis_client = RedisClient(
            args.redis_host,
            args.redis_port,
            args.redis_password,
        )

    command = [
        "docker",
        "run",
        "--rm",
        "-i",
        "--network",
        "bridge",
        "apache/kafka:4.3.1",
        "/opt/kafka/bin/kafka-console-producer.sh",
        "--bootstrap-server",
        args.bootstrap_server,
        "--topic",
        args.topic,
        "--property",
        "parse.key=true",
        "--property",
        "key.separator=|",
    ]

    process = subprocess.Popen(command, stdin=subprocess.PIPE, text=True)
    if process.stdin is None:
        raise RuntimeError("Kafka producer stdin is unavailable")

    batch_size = max(1, args.rate // 10)
    batch_interval = batch_size / args.rate
    next_flush_at = time.monotonic() + batch_interval
    waited_reservations = 0

    try:
        for index in range(args.event_count):
            reservation_id = f"{args.reservation_prefix}-{index}"

            if redis_client is not None:
                reserved_at_millis, waited = wait_for_reservation(
                    redis_client,
                    args.redis_key_prefix,
                    args.campaign_id,
                    reservation_id,
                    args.reservation_wait_timeout,
                )
                if waited:
                    waited_reservations += 1
                occurred_at = utc_after_epoch_millis(
                    reserved_at_millis
                )
            else:
                occurred_at = utc_now()

            event = {
                "eventId": f"{args.event_prefix}-{index}",
                "reservationId": reservation_id,
                "eventType": "CHARGED",
                "targetAppliedAmount": args.amount,
                "sequence": 1,
                "occurredAt": occurred_at,
            }
            process.stdin.write(
                f"{reservation_id}|{json.dumps(event, separators=(',', ':'))}\n"
            )

            published = index + 1
            if published % batch_size == 0 or published == args.event_count:
                process.stdin.flush()
                now = time.monotonic()
                if now < next_flush_at:
                    time.sleep(next_flush_at - now)
                    next_flush_at += batch_interval
                else:
                    # 예약 생성을 기다렸더라도 밀린 이벤트를 burst로
                    # 따라잡지 않고 다음 배치부터 원래 속도를 유지한다.
                    next_flush_at = now + batch_interval

            if published % max(args.rate * 10, 1) == 0:
                print(
                    f"billing published: {published} / {args.event_count}",
                    flush=True,
                )
    finally:
        process.stdin.close()
        if redis_client is not None:
            redis_client.close()

    exit_code = process.wait(timeout=60)
    if exit_code != 0:
        raise RuntimeError(f"Kafka producer failed with exit code {exit_code}")

    print(f"billing published: {args.event_count} / {args.event_count}")
    if redis_gating_enabled:
        print(
            "billing waited for reservation: "
            f"{waited_reservations} / {args.event_count}"
        )


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        print(f"Billing publish failed: {exception}", file=sys.stderr)
        sys.exit(1)
