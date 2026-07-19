package com.example.navis_test.model

// Trạng thái chuỗi kết nối CAN: binder tới CanService rồi mở socket can0.
enum class ConnectionState {
    CONNECTING,      // chưa có kết quả (mới khởi động)
    ONLINE,          // đã mở can0, vòng đọc đang chạy
    SERVICE_OFFLINE, // không lấy được binder hust.can.ICan/default
    OPEN_FAILED      // có binder nhưng mở can0 thất bại
}
