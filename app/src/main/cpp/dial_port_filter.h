#pragma once

// Fork: who may connect to the loopback USB/IP server.
//
// USB/IP has no authentication, and 127.0.0.1:<port> is reachable by every app
// on the phone that holds INTERNET. The app's own dialer is the only client the
// server should ever have, so the dialer binds its local socket to
// 127.0.0.1:<free port>, registers that port here (registerDialPort, JNI), and
// then connects. usbipdcpp's connection filter (Server::set_connection_filter,
// run on the network thread straight after accept) calls allow() below.
//
// Header-only and free of JNI and spdlog so a host g++ can test it:
// app/src/test/cpp/test_dial_port_filter.cpp.

#include <cstdint>
#include <mutex>
#include <set>

#include <asio/ip/address.hpp>
#include <asio/ip/tcp.hpp>

namespace dialfilter {

// Source ports the app's own dialer has registered. Its own mutex and nothing
// else: stopServerImpl holds g_server_mutex while Server::stop() joins the
// network thread, and the filter runs on that thread, so a filter waiting on
// g_server_mutex would deadlock. Lock order: g_server_mutex, if held at all,
// then this mutex; nothing is ever called with this mutex held.
class Registry {
public:
    void add(std::uint16_t port) {
        std::lock_guard<std::mutex> lock(mutex_);
        ports_.insert(port);
    }

    // True if the port was registered. A no-op, not an error, for a port the
    // filter has already consumed.
    bool remove(std::uint16_t port) {
        std::lock_guard<std::mutex> lock(mutex_);
        return ports_.erase(port) > 0;
    }

    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        ports_.clear();
    }

    std::size_t size() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return ports_.size();
    }

private:
    mutable std::mutex mutex_;
    std::set<std::uint16_t> ports_;
};

// Exactly IPv4 127.0.0.1. Not is_loopback(): that is true for all of
// 127.0.0.0/8, and another app can bind 127.0.0.2:P and connect from it. Not
// ::1, and not ::ffff:127.0.0.1.
inline bool is_exact_loopback_v4(const asio::ip::address& a) {
    return a.is_v4() && a.to_v4() == asio::ip::address_v4::loopback();
}

// Any address that reaches this phone without leaving it, in any notation:
// all of 127/8, ::1, and IPv4-mapped IPv6 of either, and "unspecified".
inline bool is_local_peer(const asio::ip::address& a) {
    if (a.is_unspecified() || a.is_loopback()) {
        return true;
    }
    if (a.is_v6()) {
        const auto v6 = a.to_v6();
        if (v6.is_v4_mapped()) {
            const auto v4 = asio::ip::make_address_v4(asio::ip::v4_mapped, v6);
            return v4.is_loopback() || v4.is_unspecified();
        }
    }
    return false;
}

// The connection filter. True accepts the connection.
//
// A local peer (127/8, ::1, mapped, unspecified) is accepted only if it is
// exactly 127.0.0.1 and its source port is registered, and the registration is
// consumed by that accept: loopback's tcp_tw_reuse default lets a closed port
// be handed to another app's connect(), so one registration is good for one
// connection. The address is tested first, so a connection from 127.0.0.2
// cannot use up a registration.
//
// loopback_only == true: every other peer is refused as well (the listener is
// bound to 127.0.0.1, so none should arrive; this keeps the rule closed if the
// bind ever changes).
//
// loopback_only == false (listening on all interfaces): peers that are not
// local pass, as upstream does. THIS MODE IS UNPROTECTED against local apps:
// an app that connects to the phone's own WiFi address reaches the server from
// that address, which is not loopback, so it passes. Only a registered
// 127.0.0.1 dialer is told apart from the network, and nothing here can tell
// an app from the network.
inline bool allow(const asio::ip::tcp::endpoint& peer, Registry& registry, bool loopback_only) {
    const auto addr = peer.address();
    if (is_local_peer(addr) || loopback_only) {
        if (!is_exact_loopback_v4(addr)) {
            return false;
        }
        return registry.remove(peer.port());
    }
    return true;
}

} // namespace dialfilter
