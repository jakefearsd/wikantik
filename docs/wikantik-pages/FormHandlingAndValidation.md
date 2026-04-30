---
canonical_id: 01KQ0P44QFARR7S5MQK7V03BK2
title: Form Handling and Validation
type: article
cluster: frontend-development
status: active
date: '2026-04-26'
summary: How to build forms that work — client-side and server-side validation, error
  states, submission patterns, and the form libraries that have aged well.
tags:
- forms
- validation
- frontend
- ux
- accessibility
related:
- WebAccessibilityGuide
- TypeScriptFundamentals
- IdempotencyPatterns
hubs:
- FrontendDevelopmentHub
---
# Form Handling and Validation

Forms are the workhorses of web applications. They look simple; they're full of subtleties — validation timing, error states, accessibility, security, idempotency. Most form bugs come from missing one of these.

This page covers the patterns that work.

## The validation layers

### Client-side validation

Catches errors immediately. Better UX (don't wait for server roundtrip). Provides immediate feedback.

```html
<input type="email" required>
```

HTML5 validation handles common cases. JavaScript validates more complex rules.

**Important**: client-side validation is not security. Clients can send anything; the server must validate.

### Server-side validation

The actual security boundary. Reject anything invalid; never trust client validation.

Even if the client did validate, the server validates again. Both layers are needed.

### Database constraints

Last line of defense. NOT NULL, UNIQUE, CHECK constraints. Catches anything that bypassed earlier validation.

For critical data integrity, all three layers should validate.

## Validation timing

When to show errors matters for UX:

### On submit only

Show errors only when the user submits. Too late — they have to fix multiple errors at once.

### On blur (when leaving a field)

Show errors when the user moves to the next field. Most ergonomic for most cases.

### Real-time (on input)

Show errors as user types. For specific cases (password strength, username availability), this is right. For most fields, it's annoying — errors appear before the user finishes typing.

The reasonable default: validate on blur; on submit, validate everything; for specific fields, real-time.

## Error display

Errors should be:

- **Specific**: "Email is required" not "Invalid"
- **Inline**: next to the field that's wrong
- **Persistent**: stays visible while the field is invalid
- **Linked to the field**: aria-describedby for accessibility
- **Visually clear**: color + icon + text, not just color

```html
<label for="email">Email</label>
<input id="email" type="email" aria-describedby="email-error" aria-invalid="true">
<div id="email-error" role="alert">Email is required</div>
```

Screen readers announce the error when the field is focused.

## Submission patterns

### Disable button while submitting

Prevent double-submission:

```javascript
async function submit() {
    setSubmitting(true);
    try {
        await api.post('/orders', data);
    } finally {
        setSubmitting(false);
    }
}
```

Disable the submit button while `submitting` is true.

### Idempotency keys

For network failures, retries can duplicate. Send an idempotency key:

```javascript
const idempotencyKey = uuid();
await api.post('/orders', data, {
    headers: { 'Idempotency-Key': idempotencyKey }
});
```

See [IdempotencyPatterns](IdempotencyPatterns).

### Show progress

Long-running submissions need progress indication. Spinners, progress bars, status messages.

### Success feedback

After successful submission, tell the user. Either redirect (navigation = implicit success) or show a confirmation message.

### Error recovery

When the server returns errors, map them back to the right fields. The user should see exactly which fields are wrong.

## Form libraries

For React:

### React Hook Form

The dominant choice. Performant; minimal re-renders; good DX.

```jsx
const { register, handleSubmit, formState: { errors } } = useForm();

<form onSubmit={handleSubmit(onSubmit)}>
    <input {...register('email', { required: 'Required' })} />
    {errors.email && <span>{errors.email.message}</span>}
</form>
```

### Formik

Older; still common. More re-renders than React Hook Form.

### Zod, Yup

Schema validation libraries. Pair with React Hook Form for type-safe validation.

```javascript
const schema = z.object({
    email: z.string().email(),
    age: z.number().min(18).max(120),
});

// React Hook Form integration
useForm({ resolver: zodResolver(schema) });
```

For Vue, Svelte, etc., similar libraries exist.

## Specific input types

### Email

```html
<input type="email" autocomplete="email">
```

`autocomplete` lets browsers autofill. Specify per field.

### Password

```html
<input type="password" autocomplete="current-password">
<input type="password" autocomplete="new-password">  <!-- For sign-up -->
```

The autocomplete value matters for password managers.

### Numbers

```html
<input type="number" min="0" max="100" step="1">
```

Constraints apply; mobile keyboards show numeric pad.

### Dates

```html
<input type="date">
```

Native date picker. For more control, custom components or libraries.

### Files

```html
<input type="file" accept="image/*" multiple>
```

`accept` filters; `multiple` allows several files. Server-side validation still required.

## Auto-save

For long forms (multi-step, complex data), save to local storage as user types:

```javascript
// On change
localStorage.setItem('draft-order', JSON.stringify(formData));

// On load
const draft = JSON.parse(localStorage.getItem('draft-order') || '{}');
```

Recover from accidents (browser crash, accidental navigation away).

## Multi-step forms

For long forms, break into steps:

- Each step validates before allowing next
- Progress indicator (step 2 of 5)
- Allow back to previous steps
- Save draft per step

Modern form libraries handle multi-step; manual implementation is also fine.

## Common security issues

### CSRF

Cross-Site Request Forgery — another site submits a form on behalf of the logged-in user. Mitigations:
- CSRF tokens on every state-changing request
- SameSite cookies
- Same-origin checks

Most frameworks have CSRF protection built in.

### XSS

Cross-Site Scripting — user input is reflected back without sanitization; attacker injects script.

Frameworks (React, Vue) escape by default. Don't use `dangerouslySetInnerHTML` without sanitization.

### Mass assignment

Server accepts more fields than expected. User submits extra fields; server saves them.

Always specify which fields you accept; reject the rest.

## Common failure patterns

- **No client-side validation.** Submit fails; no immediate feedback.
- **No server-side validation.** Security hole.
- **Validating on every keystroke.** Annoying; errors appear before user finishes.
- **Vague error messages.** "Invalid input" doesn't help.
- **Lost form data on error.** Form clears; user re-types everything.
- **No accessibility.** Form fields without labels, errors not announced.
- **No CSRF protection.** State-changing forms are vulnerable.

## Further Reading

- [WebAccessibilityGuide](WebAccessibilityGuide) — Forms must be accessible
- [TypeScriptFundamentals](TypeScriptFundamentals) — Type-safe form schemas
- [IdempotencyPatterns](IdempotencyPatterns) — Submission idempotency
- [FrontendDevelopment Hub](FrontendDevelopmentHub) — Cluster index
