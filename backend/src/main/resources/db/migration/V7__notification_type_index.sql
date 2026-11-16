-- V7: уведомления фильтруются по (user_id, type) при дедупликации PDF_READY
-- и SCENARIO_RECALCULATED (см. NotificationService) — без индекса это seq scan по пользователю.
CREATE INDEX idx_notifications_user_type
    ON notifications (user_id, type);
