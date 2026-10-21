package app.flowt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class Team {
    enum Role { OWNER, ADMIN, MEMBER }

    static final class Member {
        final UUID uuid;
        String name;
        Role role;

        Member(UUID uuid, String name, Role role) {
            this.uuid = uuid;
            this.name = name;
            this.role = role;
        }
    }

    String name;
    String description = "";
    boolean open;
    int score;

    boolean hasHome;
    String homeWorld;
    double hx, hy, hz;
    float hyaw, hpitch;

    final Map<UUID, Member> members = new LinkedHashMap<>();

    Team(String name) {
        this.name = name;
    }

    Member owner() {
        for (Member m : members.values()) {
            if (m.role == Role.OWNER) return m;
        }
        return null;
    }

    Role role(UUID id) {
        Member m = members.get(id);
        return m == null ? null : m.role;
    }

    Member findMember(String name) {
        for (Member m : members.values()) {
            if (m.name.equalsIgnoreCase(name)) return m;
        }
        return null;
    }
}
