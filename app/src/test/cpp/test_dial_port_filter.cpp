// Host test for app/src/main/cpp/dial_port_filter.h. Not part of the Android
// build. Run from anywhere (needs libasio-dev, g++ with C++17 or later):
//
//   g++ -std=c++20 -Wall -Wextra -DASIO_STANDALONE -pthread
//       -I app/src/main/cpp app/src/test/cpp/test_dial_port_filter.cpp
//       -o /tmp/test_dial_port_filter && /tmp/test_dial_port_filter
// (one command line; run it from the repository root)

#include <atomic>
#include <cstdio>
#include <thread>
#include <vector>

#include "dial_port_filter.h"

using asio::ip::make_address;
using asio::ip::tcp;

static int g_checks = 0;
static int g_failed = 0;

#define CHECK(cond)                                                                \
    do {                                                                           \
        ++g_checks;                                                                \
        if (!(cond)) {                                                             \
            ++g_failed;                                                            \
            std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);            \
        }                                                                          \
    } while (0)

static tcp::endpoint ep(const char* addr, unsigned short port) {
    return tcp::endpoint(make_address(addr), port);
}

int main() {
    using dialfilter::allow;
    using dialfilter::Registry;

    // loopback only: 127.0.0.1 with a registered port, accepted once
    {
        Registry r;
        r.add(40000);
        CHECK(allow(ep("127.0.0.1", 40000), r, true));
        CHECK(!allow(ep("127.0.0.1", 40000), r, true)); // reuse refused
        CHECK(r.size() == 0);
    }
    // an unregistered 127.0.0.1 port
    {
        Registry r;
        r.add(40000);
        CHECK(!allow(ep("127.0.0.1", 40001), r, true));
        CHECK(r.size() == 1); // the registered one is untouched
    }
    // 127.0.0.2 with a registered port: refused, and does not use it up
    {
        Registry r;
        r.add(40000);
        CHECK(!allow(ep("127.0.0.2", 40000), r, true));
        CHECK(!allow(ep("127.255.255.254", 40000), r, true));
        CHECK(r.size() == 1);
        CHECK(allow(ep("127.0.0.1", 40000), r, true));
    }
    // ::1 and the mapped forms with a registered port
    {
        Registry r;
        r.add(40000);
        CHECK(!allow(ep("::1", 40000), r, true));
        CHECK(!allow(ep("::ffff:127.0.0.1", 40000), r, true));
        CHECK(!allow(ep("::ffff:127.0.0.2", 40000), r, true));
        CHECK(!allow(ep("::", 40000), r, true));
        CHECK(r.size() == 1);
    }
    // loopback only: a non-loopback peer is refused too
    {
        Registry r;
        r.add(40000);
        CHECK(!allow(ep("192.0.2.20", 40000), r, true));
        CHECK(!allow(ep("198.51.100.7", 40000), r, true));
        CHECK(!allow(ep("2001:db8::1", 40000), r, true));
        CHECK(r.size() == 1);
    }
    // all interfaces: a non-loopback peer passes, registered or not
    {
        Registry r;
        CHECK(allow(ep("192.0.2.20", 51234), r, false));
        CHECK(allow(ep("198.51.100.7", 51234), r, false));
        CHECK(allow(ep("2001:db8::1", 51234), r, false));
        CHECK(allow(ep("::ffff:192.0.2.20", 51234), r, false));
    }
    // all interfaces: an unregistered loopback peer is refused, every notation
    {
        Registry r;
        CHECK(!allow(ep("127.0.0.1", 51234), r, false));
        CHECK(!allow(ep("127.0.0.2", 51234), r, false));
        CHECK(!allow(ep("::1", 51234), r, false));
        CHECK(!allow(ep("::ffff:127.0.0.1", 51234), r, false));
        CHECK(!allow(ep("0.0.0.0", 51234), r, false));
    }
    // all interfaces: a registered 127.0.0.1 is accepted once; 127.0.0.2 never
    {
        Registry r;
        r.add(40000);
        CHECK(!allow(ep("127.0.0.2", 40000), r, false));
        CHECK(allow(ep("127.0.0.1", 40000), r, false));
        CHECK(!allow(ep("127.0.0.1", 40000), r, false));
    }
    // a non-local peer does not consume a registration in all-interfaces mode
    {
        Registry r;
        r.add(40000);
        CHECK(allow(ep("192.0.2.20", 40000), r, false));
        CHECK(r.size() == 1);
    }
    // unregister before the filter ran: refused
    {
        Registry r;
        r.add(40000);
        CHECK(r.remove(40000));
        CHECK(!r.remove(40000)); // a second unregister is a harmless no-op
        CHECK(!allow(ep("127.0.0.1", 40000), r, true));
    }
    // clear drops everything (server start and stop)
    {
        Registry r;
        r.add(40000);
        r.add(40001);
        r.clear();
        CHECK(r.size() == 0);
        CHECK(!allow(ep("127.0.0.1", 40000), r, true));
    }
    // the filter works with the registry from another thread: each of N
    // registered ports is consumed exactly once across racing consumers
    {
        Registry r;
        constexpr int N = 200;
        for (int i = 0; i < N; ++i) r.add(static_cast<unsigned short>(30000 + i));
        std::atomic<int> accepted{0};
        std::vector<std::thread> ts;
        for (int t = 0; t < 4; ++t) {
            ts.emplace_back([&] {
                for (int i = 0; i < N; ++i) {
                    if (allow(ep("127.0.0.1", static_cast<unsigned short>(30000 + i)), r, true)) ++accepted;
                }
            });
        }
        for (auto& t : ts) t.join();
        CHECK(accepted == N);
        CHECK(r.size() == 0);
    }

    // Real sockets: the endpoint accept() reports is the peer's bound source
    // address and port, which is what the dialer registered
    {
        asio::io_context io;
        tcp::acceptor acc(io, tcp::endpoint(make_address("127.0.0.1"), 0));
        const auto server_ep = acc.local_endpoint();
        Registry r;

        auto dial = [&](const char* src_addr, bool register_port) {
            tcp::socket c(io);
            c.open(tcp::v4());
            c.set_option(asio::socket_base::reuse_address(false));
            c.bind(tcp::endpoint(make_address(src_addr), 0));
            const auto port = c.local_endpoint().port();
            if (register_port) r.add(port);
            c.connect(server_ep);
            tcp::socket peer(io);
            tcp::endpoint remote;
            acc.accept(peer, remote);
            return std::make_pair(allow(remote, r, true), port);
        };

        auto a = dial("127.0.0.1", true);
        CHECK(a.first);                    // our dialer: accepted
        CHECK(r.size() == 0);              // and the registration is spent
        auto b = dial("127.0.0.1", false); // another app on 127.0.0.1: unregistered port
        CHECK(!b.first);
        auto c = dial("127.0.0.2", true);  // another app bound to 127.0.0.2, port registered
        CHECK(!c.first);
        CHECK(r.size() == 1);              // the registration was not consumed by it
    }

    std::printf("%d checks, %d failed\n", g_checks, g_failed);
    return g_failed == 0 ? 0 : 1;
}
