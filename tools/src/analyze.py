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
    # Keep the earlier scheduler-based Jump Reset exports readable.
    if "JumpApplied" not in df.columns:
        df["JumpApplied"] = "unknown"
    if "SprintResetDeferred" not in df.columns:
        df["SprintResetDeferred"] = False
    if "HorizontalVelocity" not in df.columns:
        df["HorizontalVelocity"] = (
            df.get("VelocityX", 0).astype(float) ** 2
            + df.get("VelocityZ", 0).astype(float) ** 2
        ) ** 0.5
    if "JumpDelayMS" not in df.columns:
        df["JumpDelayMS"] = -1
    if "TargetHoldMS" not in df.columns:
        df["TargetHoldMS"] = -1

    df["TimeSeconds"] = (df["Timestamp"] - df["Timestamp"].min()) / 1000.0
    triggered = df[df["Outcome"] == "TRIGGERED"]
    pressed = triggered[triggered["ActualPressDelayMS"] >= 0]
    applied_mask = triggered["JumpApplied"].astype(str).str.lower().eq("true")
    applied = triggered[applied_mask]
    completed_holds = triggered[triggered["ActualHoldMS"] >= 0]
    trigger_rate = len(triggered) * 100.0 / len(df) if len(df) else 0
    applied_rate = len(applied) * 100.0 / len(triggered) if len(triggered) else 0
    deferrals = triggered["SprintResetDeferred"].astype(str).str.lower().eq("true").sum()
    average_delay = pressed["ActualPressDelayMS"].mean() if len(pressed) else 0
    average_jump_delay = applied["JumpDelayMS"].mean() if len(applied) else 0
    average_velocity = triggered["HorizontalVelocity"].mean() if len(triggered) else 0
    average_decision = df["DecisionUS"].mean() if len(df) else 0
    average_packet_gap = df["HurtToVelocityMS"].mean() if len(df) else 0
    session_time = df["TimeSeconds"].max() if len(df) else 0

    session_stats = (
        f"Session: {session_time:.1f}s | Hits: {len(df)} | Triggered: {len(triggered)} "
        f"({trigger_rate:.1f}%) | Jump applied: {len(applied)} ({applied_rate:.1f}%) | "
        f"Sprint deferrals: {deferrals}\n"
        f"Avg press: {average_delay:.3f}ms | Avg jump: {average_jump_delay:.3f}ms | "
        f"Avg horizontal velocity: {average_velocity:.3f} | "
        f"Avg decision: {average_decision:.3f}us | Avg S19->S12: {average_packet_gap:.3f}ms"
    )
    header_text = f"{session_stats}\n---\n" + "\n".join(params)

    fig = plt.figure(figsize=(16, 14))
    gs = gridspec.GridSpec(3, 2, height_ratios=[1.15, 3, 3], figure=fig)
    fig.suptitle(f"GhostTap Jump Reset Analytics: {os.path.basename(file_path)}",
                 fontsize=18, fontweight="bold", y=0.98)

    timeline_axis = fig.add_subplot(gs[1, 0])
    outcome_axis = fig.add_subplot(gs[1, 1])
    delay_axis = fig.add_subplot(gs[2, 0])
    hold_axis = fig.add_subplot(gs[2, 1])

    if len(pressed):
        timeline_axis.scatter(
            pressed["TimeSeconds"], pressed["ActualPressDelayMS"],
            s=20, alpha=0.75, label="Actual press delay", color="tab:red",
        )
        if len(applied):
            timeline_axis.scatter(
                applied["TimeSeconds"], applied["JumpDelayMS"],
                s=14, alpha=0.55, label="Observed jump", color="tab:green",
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

    safe_histplot(df["HorizontalVelocity"], delay_axis, "tab:orange")
    delay_axis.set_title("Incoming Horizontal Velocity")
    delay_axis.set_xlabel("Velocity (blocks/tick)")

    if len(completed_holds):
        safe_histplot(completed_holds["ActualHoldMS"], hold_axis, "tab:blue")
        target_holds = completed_holds[completed_holds["TargetHoldMS"] >= 0]
        if len(target_holds):
            hold_axis.axvline(target_holds["TargetHoldMS"].mean(), color="tab:red",
                              linestyle="--", label="Avg sampled target")
            hold_axis.legend(loc="upper right")
    else:
        hold_axis.text(0.5, 0.5, "No completed holds", ha="center", va="center")
    hold_axis.set_title("Synthetic Jump-key Hold")
    hold_axis.set_xlabel("Actual hold (ms)")

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
    if "ScheduledDelayMS" not in df.columns:
        df["ScheduledDelayMS"] = 0
    if "AttackGap" not in df.columns:
        if "TargetGap" in df.columns:
            df["AttackGap"] = df["TargetGap"]
        elif "TargetDistance" in df.columns:
            df["AttackGap"] = df["TargetDistance"]
    if "ConfirmedGap" not in df.columns:
        df["ConfirmedGap"] = df["AttackGap"] if "AttackGap" in df.columns else float("nan")
    if "ProjectedGap" not in df.columns:
        df["ProjectedGap"] = df["ConfirmedGap"]
    if "FinalGap" not in df.columns:
        df["FinalGap"] = float("nan")
    if "JumpDeferrals" not in df.columns:
        df["JumpDeferrals"] = 0
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
    jump_deferrals = triggered["JumpDeferrals"].sum()
    average_decision = df["DecisionUS"].mean() if len(df) else 0
    session_time = df["TimeSeconds"].max() if len(df) else 0
    modes = ", ".join(sorted(df["Mode"].dropna().unique()))

    session_stats = (
        f"Session: {session_time:.1f}s | Attacks: {len(df)} | Triggered: {len(triggered)} "
        f"({trigger_rate:.1f}%) | Modes: {modes}\n"
        f"Avg confirmation: {average_confirmation:.3f}ms | Avg action delay: {average_delay:.3f}ms | "
        f"Avg duration: {average_duration:.3f}ms | Backsteps: {backsteps} | "
        f"Jump deferrals: {jump_deferrals:.0f}\n"
        f"Avg confirmed gap: {average_gap:.2f} | Avg duration multiplier: {average_multiplier:.3f}x | "
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
            response.loc[~backstep_mask, "ProjectedGap"],
            response.loc[~backstep_mask, "TargetDurationTicks"],
            s=22, alpha=0.7, label="Reset", color="tab:green",
        )
        duration_axis.scatter(
            response.loc[backstep_mask, "ProjectedGap"],
            response.loc[backstep_mask, "TargetDurationTicks"],
            s=28, alpha=0.8, label="Backstep", color="tab:red",
        )
        duration_axis.legend(loc="upper right")
        duration_axis.set_title("Response by Projected Horizontal Gap")
        duration_axis.set_xlabel("One-tick projected hitbox gap (blocks)")
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
        "Outcome", "ActualPressDelayMS", "DecisionUS"
    }.issubset(df.columns) and (
        "JumpApplied" in df.columns or "InputTickOffset" in df.columns
    )
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
