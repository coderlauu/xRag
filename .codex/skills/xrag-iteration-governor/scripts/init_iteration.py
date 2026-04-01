#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from datetime import date
from pathlib import Path


def resolve_repo_root(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    return Path(__file__).resolve().parents[4]


def slugify(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-")
    return slug or "iteration"


def write_file(path: Path, content: str, force: bool) -> None:
    if path.exists() and not force:
        raise FileExistsError(f"File already exists: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def scaffold_handoff(
    repo_root: Path,
    version: str,
    phase: str | None,
    status: str,
    goal: str | None,
    force: bool,
    today: str,
) -> Path:
    template_path = repo_root / "docs/handoff/_template.md"
    handoff_path = repo_root / f"docs/handoff/{version}.md"
    template = template_path.read_text(encoding="utf-8")

    version_heading = f"# {version} Handoff"
    version_label = version if not phase else f"{version} / {phase}"
    body = template
    body = body.replace("# <Version> Handoff Template", version_heading)
    body = body.replace("**日期：** YYYY-MM-DD", f"**日期：** {today}")
    body = body.replace("**版本：** `<version>`", f"**版本：** `{version_label}`")
    body = body.replace("**状态：** draft / active / archived", f"**状态：** {status}")

    if goal:
        body = body.replace("用一句话说明这一轮版本要解决什么问题。", goal)

    write_file(handoff_path, body, force)
    return handoff_path


def scaffold_exec_plan(
    repo_root: Path,
    plan_id: str,
    owner: str | None,
    goal: str | None,
    related_docs: list[str],
    force: bool,
    today: str,
) -> Path:
    template_path = repo_root / "docs/exec-plans/_template.md"
    exec_plan_path = repo_root / f"docs/exec-plans/active/{today}-{plan_id}.md"
    template = template_path.read_text(encoding="utf-8")

    related_docs_text = ", ".join(related_docs) if related_docs else ""
    body = template
    body = body.replace("- `plan_id`:", f"- `plan_id`: `{plan_id}`")
    body = body.replace("- `status`: `draft | active | blocked | completed`", "- `status`: `active`")
    body = body.replace("- `owner`:", f"- `owner`: `{owner or 'unassigned'}`")
    body = body.replace("- `related_docs`:", f"- `related_docs`: {related_docs_text}")
    if goal:
        body = body.replace("一句话说明这次要完成什么。", goal)

    write_file(exec_plan_path, body, force)
    return exec_plan_path


def build_status_filename(version: str, phase: str | None) -> str:
    if phase:
        return f"{version}-{slugify(phase)}.md"
    return f"{version}.md"


def scaffold_status(
    repo_root: Path,
    version: str,
    phase: str | None,
    version_status: str,
    owner: str | None,
    goal: str | None,
    force: bool,
    today: str,
) -> Path:
    template_path = repo_root / "docs/status/_template.md"
    status_path = repo_root / "docs/status" / build_status_filename(version, phase)
    template = template_path.read_text(encoding="utf-8")

    phase_label = phase or "TBD"
    body = template
    body = body.replace("# Version Status Template", f"# {version}{f' / {phase}' if phase else ''} Status")
    body = body.replace("- `version`: `<version>`", f"- `version`: `{version}`")
    body = body.replace("- `phase`: `<phase>`", f"- `phase`: `{phase_label}`")
    body = body.replace(
        "- `status`: `not-started | in-progress | blocked | completed | archived`",
        f"- `status`: `{version_status}`"
    )
    body = body.replace("- `owner`: `unassigned`", f"- `owner`: `{owner or 'unassigned'}`")
    body = body.replace("- `updated_at`: `YYYY-MM-DD`", f"- `updated_at`: `{today}`")
    if goal:
        body = body.replace("### In Scope\n\n- ", f"### In Scope\n\n- {goal}\n- ")

    write_file(status_path, body, force)
    return status_path


def update_current(repo_root: Path, version: str, phase: str | None, handoff_path: Path, status_path: Path) -> None:
    current_path = repo_root / "docs/handoff/current.md"
    current_text = current_path.read_text(encoding="utf-8")
    version_label = version if not phase else f"{version} / {phase}"
    handoff_replacement = f"当前有效版本：[{version_label}]({handoff_path.as_posix()})"
    updated, handoff_count = re.subn(
        r"当前有效版本：\[.*?\]\([^)]+\)",
        handoff_replacement,
        current_text,
        count=1,
    )
    if handoff_count != 1:
        raise RuntimeError("Could not update current.md: expected one active-version line.")

    status_replacement = f"当前版本状态：[{version_label} Status]({status_path.as_posix()})"
    updated, status_count = re.subn(
        r"当前版本状态：\[.*?\]\([^)]+\)",
        status_replacement,
        updated,
        count=1,
    )
    if status_count != 1:
        raise RuntimeError("Could not update current.md: expected one version-status line.")
    current_path.write_text(updated, encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scaffold xRag iteration artifacts from project templates.",
    )
    parser.add_argument("--repo-root", help="Override repo root path")
    parser.add_argument("--version", required=True, help="Version file name, for example v2")
    parser.add_argument("--phase", help="Phase label, for example Phase 1B")
    parser.add_argument("--status", default="draft", help="Handoff status")
    parser.add_argument(
        "--version-status",
        default="not-started",
        help="Version status value for docs/status, for example not-started or in-progress",
    )
    parser.add_argument("--goal", help="One-line iteration goal")
    parser.add_argument("--create-exec-plan", action="store_true", help="Create an active exec plan")
    parser.add_argument("--exec-plan-id", help="Exec plan id / slug")
    parser.add_argument("--owner", help="Exec plan owner")
    parser.add_argument("--update-current", action="store_true", help="Update docs/handoff/current.md")
    parser.add_argument("--force", action="store_true", help="Overwrite existing generated files")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = resolve_repo_root(args.repo_root)
    today = date.today().isoformat()

    try:
        handoff_path = scaffold_handoff(
            repo_root=repo_root,
            version=args.version,
            phase=args.phase,
            status=args.status,
            goal=args.goal,
            force=args.force,
            today=today,
        )
        print(f"[OK] created handoff: {handoff_path}")

        status_path = scaffold_status(
            repo_root=repo_root,
            version=args.version,
            phase=args.phase,
            version_status=args.version_status,
            owner=args.owner,
            goal=args.goal,
            force=args.force,
            today=today,
        )
        print(f"[OK] created status: {status_path}")

        related_docs = [
            f"`{handoff_path.relative_to(repo_root).as_posix()}`",
            f"`{status_path.relative_to(repo_root).as_posix()}`",
        ]

        if args.create_exec_plan:
            plan_id = args.exec_plan_id or slugify(f"{args.version}-{args.goal or 'iteration'}")
            exec_plan_path = scaffold_exec_plan(
                repo_root=repo_root,
                plan_id=plan_id,
                owner=args.owner,
                goal=args.goal,
                related_docs=related_docs,
                force=args.force,
                today=today,
            )
            print(f"[OK] created exec plan: {exec_plan_path}")

        if args.update_current:
            update_current(
                repo_root=repo_root,
                version=args.version,
                phase=args.phase,
                handoff_path=handoff_path,
                status_path=status_path,
            )
            print(f"[OK] updated current handoff: {repo_root / 'docs/handoff/current.md'}")
    except Exception as exc:  # noqa: BLE001
        print(f"[ERROR] {exc}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
