package ch.amicalewifi.service;

import ch.amicalewifi.model.Member;
import ch.amicalewifi.model.Presence;

import java.math.BigDecimal;

public sealed interface ScanResult
        permits ScanResult.Granted, ScanResult.Denied, ScanResult.NewMember {

    record Granted(Member member, Presence presence,
                   BigDecimal packRemaining, Integer halfDays) implements ScanResult {}

    record Denied(String reason, Member member) implements ScanResult {}

    record NewMember(String badgeUid) implements ScanResult {}
}
