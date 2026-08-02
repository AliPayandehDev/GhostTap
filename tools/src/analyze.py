import glob
import os
import sys

import matplotlib.gridspec as gridspec
import matplotlib.pyplot as plt
import pandas as pd
import seaborn as sns


def get_latest_stats_file(folder_path):
    files = glob.glob(os.path.join(folder_path, "analytics_*.csv"))
    return max(files, key=os.path.getctime) if files else None


def resolve_input_path():
    if len(sys.argv) > 1:
        requested = sys.argv[1]
        if os.path.isfile(requested):
            return requested
        if os.path.isdir(requested):
            return get_latest_stats_file(requested)
        print(f"Error: Analytics path does not exist: {requested}")
        return None
    return get_latest_stats_file("analytics")


def read_metadata(file_path):
    params = []
    with open(file_path, "r", encoding="utf-8") as source:
        for line in source:
            if line.startswith("#") and "METADATA" not in line:
                params.append(line.replace("#", "", 1).strip())
            elif line.startswith("Timestamp"):
                break
    return params


def add_header(fig, slot, text):
    header_ax = fig.add_subplot(slot)
    header_ax.axis("off")
    header_ax.text(
        0.01, 0.8, text, fontsize=11, family="monospace",
        verticalalignment="center", wrap=True,
        bbox=dict(facecolor="white", alpha=0.8, edgecolor="gray"),
    )


def safe_histplot(series, axis, color):
    values = series.dropna()
    if values.empty:
        axis.text(0.5, 0.5, "No completed samples", ha="center", va="center")
        return
    sns.histplot(values, kde=len(values) > 1 and values.nunique() > 1, ax=axis, color=color)


def plot_clicker(df, file_path, params):
    df["TimeSeconds"] = (df["Timestamp"] - df["Timestamp"].min()) / 1000.0
    session_time = df["TimeSeconds"].max() if not df.empty else 0
    avg_actual_cps = df["ActualCPS"].mean() if not df.empty else 0
    avg_hold_ms = df["HoldMS"].mean() if not df.empty else 0

    session_stats = (
        f"Session Duration: {session_time:.1f}s | Total Clicks: {len(df)} | "
        f"Avg Speed: {avg_actual_cps:.2f} CPS | Avg Hold: {avg_hold_ms:.1f}ms"
    )
    header_text = f"{session_stats}\n---\n" + "\n".join(params)

    fig = plt.figure(figsize=(16, 14))
    gs = gridspec.GridSpec(3, 2, height_ratios=[1, 3, 3], figure=fig)
    fig.suptitle(f"GhostTap Click Analytics: {os.path.basename(file_path)}",
                 fontsize=18, fontweight="bold", y=0.98)

    cps_axis = fig.add_subplot(gs[1, 0])
    trend_axis = fig.add_subplot(gs[1, 1])
    hold_axis = fig.add_subplot(gs[2, 0])
    interval_axis = fig.add_subplot(gs[2, 1])

    cps_axis.plot(df["TimeSeconds"], df["TargetCPS"], label="Target", alpha=0.3, color="blue")
    cps_axis.plot(df["TimeSeconds"], df["ActualCPS"], label="Actual", color="red", linewidth=1.5)
    cps_axis.set_title("CPS Performance")
    cps_axis.set_ylabel("CPS")
    cps_axis.legend(loc="upper right")

    trend_axis.plot(df["TimeSeconds"], df["Trend"], color="purple", alpha=0.8)
    trend_axis.axhline(0, color="black", linestyle="--", alpha=0.3)
    trend_axis.set_title("Humanization Trend")
    trend_axis.set_ylabel("Trend Offset")

    safe_histplot(df["HoldMS"], hold_axis, "green")
    hold_axis.set_title("Hold Duration")
    hold_axis.set_xlabel("Hold Duration (ms)")

    safe_histplot(df["IntervalMS"], interval_axis, "orange")
    interval_axis.set_title("Interval Distribution")
    interval_axis.set_xlabel("Interval Duration (ms)")

    add_header(fig, gs[0, :], header_text)
    return fig


def plot_jump_reset(df, file_path, params):
    df["TimeSeconds"] = (df["Timestamp"] - df["Timestamp"].min()) / 1000.0
    triggered = df[df["Outcome"] == "TRIGGERED"]
    pressed = triggered[triggered["ActualPressDelayMS"] >= 0]
    observed_ticks = triggered[triggered["InputTickOffset"] >= 0]

    trigger_rate = len(triggered) * 100.0 / len(df) if len(df) else 0
    same_tick_rate = (
        (observed_ticks["InputTickOffset"] == 0).mean() * 100.0
        if len(observed_ticks) else 0
    )
    average_delay = pressed["ActualPressDelayMS"].mean() if len(pressed) else 0
    average_decision = df["DecisionUS"].mean() if len(df) else 0
    average_packet_gap = df["HurtToVelocityMS"].mean() if len(df) else 0
    session_time = df["TimeSeconds"].max() if len(df) else 0

    session_stats = (
        f"Session: {session_time:.1f}s | Hits: {len(df)} | Triggered: {len(triggered)} "
        f"({trigger_rate:.1f}%) | Same input tick: {same_tick_rate:.1f}%\n"
        f"Avg actual delay: {average_delay:.3f}ms | Avg decision cost: {average_decision:.3f}us | "
        f"Avg S19->S12: {average_packet_gap:.3f}ms"
    )
    header_text = f"{session_stats}\n---\n" + "\n".join(params)

    fig = plt.figure(figsize=(16, 14))
    gs = gridspec.GridSpec(3, 2, height_ratios=[1.15, 3, 3], figure=fig)
    fig.suptitle(f"GhostTap Jump Reset Analytics: {os.path.basename(file_path)}",
                 fontsize=18, fontweight="bold", y=0.98)

    timeline_axis = fig.add_subplot(gs[1, 0])
    outcome_axis = fig.add_subplot(gs[1, 1])
    delay_axis = fig.add_subplot(gs[2, 0])
    tick_axis = fig.add_subplot(gs[2, 1])

    if len(pressed):
        timeline_axis.scatter(
            pressed["TimeSeconds"], pressed["ActualPressDelayMS"],
            s=20, alpha=0.75, label="Actual press delay", color="tab:red",
        )
        timeline_axis.scatter(
            pressed["TimeSeconds"], pressed["ScheduledDelayMS"],
            s=14, alpha=0.45, label="Scheduled delay", color="tab:blue",
        )
        timeline_axis.legend(loc="upper right")
    else:
        timeline_axis.text(0.5, 0.5, "No completed presses", ha="center", va="center")
    timeline_axis.set_title("Hit-relative Press Timing")
    timeline_axis.set_xlabel("Session time (s)")
    timeline_axis.set_ylabel("Delay (ms)")

    outcome_counts = df["Outcome"].value_counts().sort_values(ascending=True)
    outcome_counts.plot(kind="barh", ax=outcome_axis, color="tab:purple")
    outcome_axis.set_title("Decision Outcomes")
    outcome_axis.set_xlabel("Events")

    safe_histplot(pressed["ActualPressDelayMS"], delay_axis, "tab:orange")
    delay_axis.set_title("Actual Packet-to-Space Delay")
    delay_axis.set_xlabel("Delay (ms)")

    if len(observed_ticks):
        tick_counts = observed_ticks["InputTickOffset"].astype(int).value_counts().sort_index()
        tick_counts.plot(kind="bar", ax=tick_axis, color="tab:green")
        tick_axis.set_xticklabels([str(value) for value in tick_counts.index], rotation=0)
    else:
        tick_axis.text(0.5, 0.5, "No movement-tick observations", ha="center", va="center")
    tick_axis.set_title("Movement Input Tick Offset")
    tick_axis.set_xlabel("Ticks after hit input tick (0 = same)")
    tick_axis.set_ylabel("Presses")

    add_header(fig, gs[0, :], header_text)
    return fig


def plot_sprint_reset(df, file_path, params):
    # Keep exports from the earlier W-only analytics schema readable.
    df = df.rename(columns={
        "ActualReleaseDelayMS": "ActualActionDelayMS",
        "TargetReleaseMS": "TargetDurationMS",
        "ActualReleaseMS": "ActualDurationMS",
        "PhysicalWDown": "PhysicalActionDown",
    })
    if "Mode" not in df.columns:
        df["Mode"] = "W_TAP"
    if "ConfirmationDelayMS" not in df.columns:
        df["ConfirmationDelayMS"] = -1
    if "AttackGap" not in df.columns:
        if "TargetGap" in df.columns:
            df["AttackGap"] = df["TargetGap"]
        elif "TargetDistance" in df.columns:
            df["AttackGap"] = df["TargetDistance"]
    if "ConfirmedGap" not in df.columns:
        df["ConfirmedGap"] = df["AttackGap"] if "AttackGap" in df.columns else float("nan")
    if "Backstep" not in df.columns:
        df["Backstep"] = False
    df["TimeSeconds"] = (df["Timestamp"] - df["Timestamp"].min()) / 1000.0
    triggered = df[df["Outcome"] == "TRIGGERED"]
    confirmed = triggered[triggered["ConfirmationDelayMS"] >= 0]
    started = triggered[triggered["ActualActionDelayMS"] >= 0]
    completed = triggered[triggered["ActualDurationMS"] >= 0]

    trigger_rate = len(triggered) * 100.0 / len(df) if len(df) else 0
    average_confirmation = confirmed["ConfirmationDelayMS"].mean() if len(confirmed) else 0
    average_delay = started["ActualActionDelayMS"].mean() if len(started) else 0
    average_duration = completed["ActualDurationMS"].mean() if len(completed) else 0
    average_gap = confirmed["ConfirmedGap"].mean() if len(confirmed) else 0
    average_multiplier = completed["DurationMultiplier"].mean() if "DurationMultiplier" in completed.columns else 1
    backsteps = triggered["Backstep"].astype(str).str.lower().eq("true").sum()
    average_decision = df["DecisionUS"].mean() if len(df) else 0
    session_time = df["TimeSeconds"].max() if len(df) else 0
    modes = ", ".join(sorted(df["Mode"].dropna().unique()))

    session_stats = (
        f"Session: {session_time:.1f}s | Attacks: {len(df)} | Triggered: {len(triggered)} "
        f"({trigger_rate:.1f}%) | Modes: {modes}\n"
        f"Avg confirmation: {average_confirmation:.3f}ms | Avg action delay: {average_delay:.3f}ms | "
        f"Avg duration: {average_duration:.3f}ms | Backsteps: {backsteps}\n"
        f"Avg horizontal gap: {average_gap:.2f} | Avg duration multiplier: {average_multiplier:.3f}x | "
        f"Avg decision cost: {average_decision:.3f}us"
    )
    header_text = f"{session_stats}\n---\n" + "\n".join(params)

    fig = plt.figure(figsize=(16, 14))
    gs = gridspec.GridSpec(3, 2, height_ratios=[1.15, 3, 3], figure=fig)
    fig.suptitle(f"GhostTap Sprint Reset Analytics: {os.path.basename(file_path)}",
                 fontsize=18, fontweight="bold", y=0.99)

    timeline_axis = fig.add_subplot(gs[1, 0])
    outcome_axis = fig.add_subplot(gs[1, 1])
    delay_axis = fig.add_subplot(gs[2, 0])
    duration_axis = fig.add_subplot(gs[2, 1])

    if len(started):
        timeline_axis.scatter(
            started["TimeSeconds"], started["ActualActionDelayMS"],
            s=20, alpha=0.75, label="Actual action delay", color="tab:red",
        )
        timeline_axis.scatter(
            started["TimeSeconds"], started["ScheduledDelayMS"],
            s=14, alpha=0.45, label="Delay", color="tab:blue",
        )
        if len(confirmed):
            timeline_axis.scatter(
                confirmed["TimeSeconds"], confirmed["ConfirmationDelayMS"],
                s=14, alpha=0.45, label="Hit confirmation", color="tab:green",
            )
        timeline_axis.legend(loc="upper right")
    else:
        timeline_axis.text(0.5, 0.5, "No started reset actions", ha="center", va="center")
    timeline_axis.set_title("Attack-relative Confirmation and Reset Timing")
    timeline_axis.set_xlabel("Session time (s)")
    timeline_axis.set_ylabel("Delay (ms)")

    outcome_counts = df["Outcome"].value_counts().sort_values(ascending=True)
    outcome_counts.plot(kind="barh", ax=outcome_axis, color="tab:purple")
    outcome_axis.set_title("Decision Outcomes")
    outcome_axis.set_xlabel("Attacks")

    safe_histplot(started["ActualActionDelayMS"], delay_axis, "tab:orange")
    delay_axis.set_title("Actual Attack-to-Action Delay")
    delay_axis.set_xlabel("Delay (ms)")

    response = completed.dropna(subset=["ConfirmedGap"])
    if len(response) and "TargetDurationTicks" in response.columns:
        backstep_mask = response["Backstep"].astype(str).str.lower().eq("true")
        duration_axis.scatter(
            response.loc[~backstep_mask, "ConfirmedGap"],
            response.loc[~backstep_mask, "TargetDurationTicks"],
            s=22, alpha=0.7, label="Reset", color="tab:green",
        )
        duration_axis.scatter(
            response.loc[backstep_mask, "ConfirmedGap"],
            response.loc[backstep_mask, "TargetDurationTicks"],
            s=28, alpha=0.8, label="Backstep", color="tab:red",
        )
        duration_axis.legend(loc="upper right")
        duration_axis.set_title("Response by Confirmed Horizontal Gap")
        duration_axis.set_xlabel("Hitbox gap (blocks)")
        duration_axis.set_ylabel("Target duration (ticks)")
    else:
        safe_histplot(completed["ActualDurationMS"], duration_axis, "tab:green")
        duration_axis.set_title("Actual Tap Duration")
        duration_axis.set_xlabel("Duration (ms)")

    add_header(fig, gs[0, :], header_text)
    return fig


def run_analysis():
    file_path = resolve_input_path()
    if not file_path:
        print("Error: No analytics files found!")
        return 1

    params = read_metadata(file_path)
    df = pd.read_csv(file_path, comment="#")
    if df.empty:
        print(f"Error: Analytics file has no rows: {file_path}")
        return 1

    plt.style.use("seaborn-v0_8-darkgrid")
    is_sprint_reset = {
        "Outcome", "Mode", "ActualActionDelayMS", "ActualDurationMS", "DecisionUS"
    }.issubset(df.columns) or {
        "Outcome", "ActualReleaseDelayMS", "ActualReleaseMS", "DecisionUS"
    }.issubset(df.columns)
    is_jump_reset = {
        "Outcome", "ActualPressDelayMS", "InputTickOffset", "DecisionUS"
    }.issubset(df.columns)
    if is_sprint_reset:
        fig = plot_sprint_reset(df, file_path, params)
    elif is_jump_reset:
        fig = plot_jump_reset(df, file_path, params)
    else:
        fig = plot_clicker(df, file_path, params)

    output_folder = "plots"
    os.makedirs(output_folder, exist_ok=True)
    output_name = os.path.basename(file_path).replace(".csv", ".png")
    fig.tight_layout(rect=[0, 0.02, 1, 0.95])
    fig.savefig(os.path.join(output_folder, output_name), dpi=300)

    print(f"Analysis Complete: {output_name}")
    plt.show()
    return 0


if __name__ == "__main__":
    raise SystemExit(run_analysis())
