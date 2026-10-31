//! Android socket protection.
//!
//! Android 的 `VpnService` 要求 VPN 应用把"到服务器的连接"从 VPN 路由中豁免，
//! 否则这些连接会被投回自己创建的 tun 设备，形成死循环。官方 API 是
//! `VpnService.protect(int fd)`。
//!
//! EasyTier 核心（`easytier::socket_protector`）已定义跨平台 trait
//! [`NativeSocketProtector`]，并在每次创建 socket 时（bind/connect 之前）调用。
//! 鸿蒙端已有实现（`easytier-ohrs`），本模块补齐 Android 端。
//!
//! 与鸿蒙的差异：`VpnService.protect` 是同步调用，因此不需要 request/ack 队列，
//! 直接拿 JVM 调用即可。

use std::sync::{Arc, OnceLock, RwLock};

use easytier::socket_protector::{NativeSocketProtector, set_native_socket_protector};
use jni::{JavaVM, objects::GlobalRef};

/// Java 侧 `EasyTierVpnService` 实例的全局引用。
static VPN_SERVICE: OnceLock<RwLock<Option<GlobalRef>>> = OnceLock::new();

/// JavaVM，用于在 tokio 线程上 attach。
static JVM: OnceLock<JavaVM> = OnceLock::new();

fn vpn_service_slot() -> &'static RwLock<Option<GlobalRef>> {
    VPN_SERVICE.get_or_init(|| RwLock::new(None))
}

/// 由 JNI 入口保存 JavaVM。
pub fn set_java_vm(vm: JavaVM) {
    let _ = JVM.set(vm);
}

/// 保存 Java 侧 VpnService 实例，并注册全局 socket protector。
pub fn install(service: GlobalRef) -> Result<(), String> {
    let mut slot = vpn_service_slot()
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *slot = Some(service);
    drop(slot);

    set_native_socket_protector(Some(
        Arc::new(AndroidSocketProtector) as Arc<dyn NativeSocketProtector>
    ));
    Ok(())
}

/// 注销 socket protector（VPN 断开时调用）。
pub fn uninstall() {
    set_native_socket_protector(None);
    let mut slot = vpn_service_slot()
        .write()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    *slot = None;
}

struct AndroidSocketProtector;

#[async_trait::async_trait]
impl NativeSocketProtector for AndroidSocketProtector {
    async fn protect(&self, socket_handle: u64) -> std::io::Result<()> {
        protect_impl(socket_handle)
    }
}

fn protect_impl(socket_handle: u64) -> std::io::Result<()> {
    let fd = i32::try_from(socket_handle).map_err(|_| {
        std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("socket handle {socket_handle} 无法转换为 Android fd"),
        )
    })?;

    let service = {
        let guard = vpn_service_slot()
            .read()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        match guard.as_ref() {
            Some(s) => s.clone(),
            // VPN 未就绪时直接放行，避免阻断正常建连。
            None => return Ok(()),
        }
    };

    let vm = JVM.get().ok_or_else(|| {
        std::io::Error::new(std::io::ErrorKind::NotConnected, "JavaVM 尚未初始化")
    })?;

    let mut env = vm.attach_current_thread().map_err(|e| {
        std::io::Error::other(format!("attach_current_thread 失败: {e}"))
    })?;

    let result = env
        .call_method(
            service.as_obj(),
            "protectSocketFd",
            "(I)Z",
            &[jni::objects::JValue::Int(fd)],
        )
        .and_then(|v| v.z());

    match result {
        Ok(true) => Ok(()),
        Ok(false) => Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            format!("VpnService.protect(fd={fd}) 返回 false"),
        )),
        Err(e) => Err(std::io::Error::other(format!(
            "调用 VpnService.protect(fd={fd}) 失败: {e}"
        ))),
    }
}
