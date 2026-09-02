package acme.authz

default allow := false

allow if {
	input.action == "activate-agent-version"
	"agent-admin" in input.actor.roles
}
