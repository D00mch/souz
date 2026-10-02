```json
{
  "kind": "event",
  "seq": 1,
  "type": "tool.call.started",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "payload": {
    "toolCallId": "call-1",
    "name": "device.list",
    "userId": "new-user",
    "arguments": {
       "types": ["StarOS", "SmartHome"]
    },
    "deadlineAt": "2026-09-30T12:00:30Z"
  },
  "createdAt": "2026-09-30T12:00:00Z"
}
```

```json
{
  "kind": "tool.result",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "toolCallId": "call-1",
  "status": "succeeded",
  "result": {
    "devices": [
      {
        "deviceId": "orion-speaker-1",
        "name": "Kitchen speaker",
        "model": "sberboom-mini",
        "categories": [
          "default"
        ],
        "locations": [
          {
            "homeId": "home-example",
            "homeName": "Example home",
            "roomName": "Kitchen"
          }
        ],
        "online": true,
        "onlineUpdatedAt": "2023-08-26T19:43:34.945Z"
      },
      {
        "deviceId": "orion-light-1",
        "name": "Dimmable light",
        "model": "yeelink.light.monoa",
        "categories": [
          "light"
        ],
        "locations": [
          {
            "homeId": "home-example",
            "homeName": "Example home"
          }
        ],
        "online": false,
        "onlineUpdatedAt": "2025-12-23T07:58:08.317Z"
      },
      {
        "deviceId": "orion-sensor-1",
        "name": "Temperature sensor",
        "model": "TS0201",
        "categories": [
          "sensor_temp"
        ],
        "locations": [
          {
            "homeId": "home-example",
            "homeName": "Example home",
            "roomName": "Hallway"
          }
        ]
      }
    ]
  }
}
```

```json
{
  "kind": "ack",
  "chatId": "10000000-0000-4000-8000-000000000001",
  "threadId": "20000000-0000-4000-8000-000000000001",
  "toolCallId": "call-1",
  "status": "accepted",
  "duplicate": false,
  "error": null,
  "receivedAt": "2026-09-30T12:00:01Z"
}
```
