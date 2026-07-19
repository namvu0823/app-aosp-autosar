package com.example.navis_test.model

// Kết quả một lượt ghi ngưỡng cảnh báo (UDS WriteDataByIdentifier, DID F1 03).
// Mã lỗi giữ nguyên quy ước cũ của app: 01 = chưa kết nối, 02 = gửi bus thất bại,
// 03 = input không hợp lệ, 04 = ECU không phản hồi (timeout).
sealed class ThresholdEvent {
    object WriteOk : ThresholdEvent()
    data class WriteFailed(val code: String) : ThresholdEvent()

    // Giá trị đọc lại từ ECU sau khi ghi (chỉ để hiển thị đối chiếu,
    // không dùng để phán ghi thành/bại — lệnh ghi đã được 6E xác nhận).
    data class Readback(val value: Int) : ThresholdEvent()
}
